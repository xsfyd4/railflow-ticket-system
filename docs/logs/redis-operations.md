# Redis 操作日志

## 2026-10-28：冷缓存重建锁与 TTL 抖动

**Key**

```text
ticket:search:rebuild-lock:{cacheKey}
```

**参数**

- 重建锁 TTL：3000ms；
- owner：随机 UUID；
- 释放：Lua 比较 owner 后删除；
- 查票结果 TTL：10 秒 ±20%；
- 空结果 TTL：约 3 秒 ±20%；
- 未抢到锁等待：20/40/80/160/320ms。

**故障与降级**

- Redis 锁不可用时直接使用本地索引；
- 等待超时后仍使用本地索引，不返回错误；
- 索引未就绪时才回落完整 MySQL 慢查询；
- 锁释放失败只等待 TTL 自动过期，不盲目删除其他实例的锁。

**验证**

- 清空 cache Redis 后，64 并发请求同一热点只产生 1 次 HTTP 线程查票 SQL。
- 39602 个请求全部成功，0 失败，7896.23 QPS，P99 22.45ms。
- 测试结束后 `ticket:search:rebuild-lock:*` 为空。

## 2026-10-28：综合读写压测下的 cache/stock 采样

**混合流量**

- 64 并发、30 秒，90% 查票、5% 下单后取消、3% 订单列表、2% 支付幂等。
- cache Redis 峰值采样 CPU 94.42%，内存 14.87MiB。
- stock Redis Master 峰值采样 CPU 55.72%，内存 8.672MiB。
- 后端容器 CPU 约 7.65 核，MySQL `Threads_running=7`。

**库存一致性**

- 压测前 10 个车次、10-29 二等座余票总量为 23649。
- 同步下单取消和异步下单取消全部完成后，余票总量仍为 23649。
- 测试结束后 cacheRedis、stockRedis 健康检查均为 `ok`。

## 2026-10-28：1000 车次重建后刷新 Redis

**原因**

- 数据库中的车次、分段和库存已经全部重建。
- 旧 cache Redis 查票对象和 stock Redis 数字库存不能继续使用。

**执行**

- 确认 cache Master 为 `172.18.0.40`，执行 `FLUSHDB`。
- Sentinel 确认 stock Master 为 `172.18.0.51`，在其主节点执行 `FLUSHDB`。
- 重启后端，重新构建本地车段车次索引并启动库存/查票预热。

**验证**

```text
RouteIndex: routes=538, relations=8400, build=526ms
StockMaintenance: 首轮扫描=5000，补建=5000
SearchWarmup: 线路=40，席别查询=240
stock Redis DBSIZE: 5000
cacheRedis / stockRedis / rabbitmq: ok
```

**结论**

- cache 和 stock 两套 Redis 均保持物理隔离。
- 旧缓存清空后，新数据的查票和预扣链路已恢复。
- stock 库存预热是分批进行，首轮完成 5000 条，不等同于 284400 条已全部进入 Redis。

## 2026-10-28：车段索引、熔断与双物理 Redis

**拓扑**

| 数据组 | Master | Replica | Sentinel | 职责 |
| --- | --- | --- | --- | --- |
| cache | `172.18.0.40` | `.41/.42` | `.43/.44/.45` | 查票对象、版本 |
| stock | `172.18.0.50` | `.51/.52` | `.53/.54/.55` | 库存、UUID、租约 |

每个数据组独立 1 Master + 2 Replica + 3 Sentinel。

**Key 隔离**

```text
cache:
ticket:search:v*
ticket:search:version:*

stock:
ticket:stock:*
ticket:stock-reconcile:*
ticket:order:idempotency:*
ticket:pay:idempotency:*
```

验证结果：

- cache Master 有查询对象，`ticket:stock:*` 数量为 0；
- stock Master 有库存 Key，查询对象数量为 0；
- UUID 状态只写 stock Master。

**熔断**

- 连续失败 3 次后打开，冷却 15 秒；
- 冷却期间查票直接 MySQL；
- 冷却结束后自动探测。

实测停止整个 cache 组：

```text
第 1 次查票：约 6098ms，HTTP 200
第 2 次查票：约 3020ms，HTTP 200
第 3 次查票：约 9ms，HTTP 200
第 4 次查票：约 9ms，HTTP 200
```

此时 stock Redis 和 RabbitMQ 均为 `ok`。

**stock 故障转移**

- 停止 stock Master 后，三个 stock Sentinel 一致切换到新 Master。
- 后端 stock 健康检查自动恢复。
- cache Redis 全程保持 `ok`。
- 原 stock Master 启动后作为新 Master 的 Replica 重新同步。

**索引更新**

- 静态数据变更先标记 `REBUILDING`。
- 再递增 cache Redis 全局版本。
- 后台索引完成原子替换后才恢复快查询。
- `UNAVAILABLE` 或重建期间全部使用 MySQL 慢查询。

## 2026-10-27～28：升级为读写分离高可用架构

**目标拓扑**

- `redis-master` 服务：固定地址 `172.18.0.20:6379`；
- `redis-replica-1/2`：固定地址 `172.18.0.21:6379`、`172.18.0.22:6379`；
- `redis-sentinel-1/2/3`：固定地址 `172.18.0.23`～`172.18.0.25`，quorum=2；
- 应用主写：Jedis Sentinel Pool，只连接当前 Master；
- 应用读模型：`ReplicaRedisReader` 向 Sentinel 查询副本并轮询读取。

**数据职责**

- Master 写：分段库存、查票版本、UUID 幂等状态、快照修复；
- Replica 读：`ticket:search:*` 热点查票快照；
- MySQL：订单、支付和库存最终事实。

**Key 模式**

```text
ticket:search:v{globalVersion}:{dateVersion}:{query}
ticket:search:version:global
ticket:search:version:date:{travelDate}
ticket:stock:{travelDate:trainId}:seatType:segmentIndex
ticket:stock-reconcile:{travelDate:trainId}
ticket:order:idempotency:{requestId}
ticket:pay:idempotency:{requestId}
```

- 查票快照：String，单对象平行数组；
- 数字库存：String，值为余票数字；
- UUID 状态：Hash，字段包含 userId、requestHash、ownerToken、state、
  resultNo、failReason、updatedAt；
- 对账租约：String，5 秒短 TTL；
- 查票快照 TTL 由 `ticket.cache.search-ttl-seconds` 控制；
- UUID PROCESSING 10 分钟、SUCCESS 7 天、FAILED 1 天；
- 库存 TTL 至少到乘车日期后 2 天。

**读写路由**

- 库存 Lua、版本号、查票快照写入、UUID 状态、对账修复全部走 Master；
- 查票快照优先轮询两个 Replica；
- Replica 不可用时尝试另一个并回退 Master；
- Redis 全部不可用时查票走 MySQL，同步下单走 MySQL 唯一键和条件 UPDATE。

**预热和对账**

- 库存预热只使用 `SET NX`，不覆盖已有预扣值；
- 预热和对账使用跨轮主键游标，单次最多 5000 行；
- 对账发现差异后先拿日期+车次 5 秒租约；
- 预扣 Lua 检查租约，对账期间不开始新预扣；
- 同步下单后的库存增量 Lua 也检查租约，对账期间暂缓调整；
- 对账再检查 `order_request.status in (0,1,2)`，有活跃请求则跳过；
- 安全时以 MySQL 修正 Redis，并递增对应日期查票版本。

**故障演练结果**

1. 一台 Replica 停止后，HTTP 查票仍成功；恢复后重新同步。
2. Master 停止后，三个 Sentinel 完成 `sdown → odown → elected-leader →
   promoted-slave → switch-master`，一致指向新 Master。
3. 后端 Jedis Sentinel Pool 自动跟随新 Master，`infra-check` 的 Redis 恢复为 `ok`。
4. 原 Master 重启后角色自动变成 Replica，`master_link_status:up`。
5. Sentinel 新拓扑写入 `/data/sentinel.conf`，容器重启后仍保留。

**演练中发现并修复的缺陷**

- 原 Sentinel 使用容器名 `redis-master` 监控。容器停止后 Docker DNS 同步消失，
  Sentinel 报 `Failed to resolve hostname` 并进入 `tilt`。已改为固定 IP。
- 原配置为只读绑定挂载，日志持续出现 `Could not create tmp config file`。
  已改为模板只读挂载 + `/data/sentinel.conf` 命名卷。

**运行验证**

- `infra-check`：Redis `ok`、RabbitMQ 发送和接收正常。
- 真实用户登录和查票成功。
- 当前复制状态为 1 Master + 2 online Replica。
- 首次预热补建 5000 个库存 Key、240 个查票对象。
- 首次对账扫描 2844 行并修复 702 个差异。

## 2026-10-24：数据库重建后清理旧缓存

数据库重建会改变车站、车次和库存 id，旧 Redis 缓存不能继续使用。

执行：

```powershell
docker exec redis-ticket redis-cli EVAL `
  "local keys=redis.call('KEYS','ticket:*'); for _,k in ipairs(keys) do redis.call('DEL',k) end; return #keys" 0
```

本次清理了重建前遗留的 `ticket:*` Key。随后完整测试又产生的 3 个 2099 年测试版本 Key
也会在测试收尾时清理，业务库存 Key 会按新数据库首次查询或预扣时重新建立。

## 2026-10-24：分段数字库存与 Lua 原子预扣

**Key**

```text
ticket:stock:{travelDate:trainId}:seatType:segmentIndex
```

示例：

```text
ticket:stock:{2099-08-01:1}:3:1
```

**类型和 TTL**

- String，值是剩余票数。
- 同一车次同一天使用 Hash Tag，Redis Cluster 下可执行多 Key Lua。
- TTL 至少保留到乘车日期后，并设置至少 2 天的最低保留期。

**写路径**

1. 异步请求第一次预扣时，从 MySQL 当前余票 `SET NX` 初始化。
2. 一段 Lua 先检查所有分段，再统一 `DECRBY`。
3. 任一段不足时返回 0，不扣任何段。
4. 消费失败时按请求人数 `INCRBY` 回补。
5. 同步下单、取消、退票、超时和管理员调整会在事务提交后同步数字。
6. Key 缺失时从 MySQL `ticket_stock` 重建，避免凭空补票。

**故障策略**

- 查票缓存继续降级 MySQL。
- 异步下单依赖数字库存准入，Redis 不可用时明确失败，不冒险排队。
- 同步下单仍由 MySQL 条件 UPDATE 保证不超卖。
- Redis 错误写入 `backend/logs/redis.log`。

**验证**

- 4 段购买 3 张后，4 个 Key 都为 7。
- 再购买 8 张返回不足，4 个 Key 仍为 7。
- 回补 3 张后，4 个 Key 都为 10。
- 自动化测试产生的 2099 年临时库存 Key 已清理 13 个。

## 当前状态

截至 2026-10-24：

- Redis 已参与查票业务。
- 客户端从 Lettuce 切换为同步 Jedis。
- 查票结果使用 Cache-Aside 模式缓存。
- Redis 不可用时可降级到 MySQL。

## 当前运行日志

Redis/Jedis 日志写入：

```text
backend/logs/redis.log
```

## 后续写入规则

每次 Redis 改造必须记录：

- Key 模式
- Value 类型
- TTL
- 写路径和读路径
- 失效点
- 回源策略
- 故障降级

## 当前 Key

查票结果：

```text
ticket:search:v{globalVersion}:{dateVersion}:
{travelDate}:{fromStationId}:{toStationId}:
{seatType}:{page}:{size}
```

全局版本：

```text
ticket:search:version:global
```

日期版本：

```text
ticket:search:version:date:{travelDate}
```

## TTL

```yaml
ticket:
  cache:
    enabled: true
    search-ttl-seconds: 10
```

## 失效规则

- 下单：递增对应日期版本。
- 取消：递增对应日期版本。
- 退票：递增对应日期版本。
- 超时取消：递增对应日期版本。
- 管理员调整库存：递增对应日期版本。
- 批量生成库存：递增全局版本。
- 停站、票价、车次或车站修改：递增全局版本。

事务内操作通过 `TransactionSynchronization.afterCommit` 失效，避免数据库回滚后缓存却被清除。

## 测试结果

- 相同版本命中 Redis，不查询 MySQL。
- 库存调整提交后回源 MySQL。
- 下单、取消、退票后均能读到新余票。
- Redis 指向不可用端口时仍可查票。

## 当时的后续项（现已完成）

- Redis Lua 预扣：已完成。
- 幂等请求号：已完成，并增加 Redis UUID 快状态。
- Redis 与 MySQL 对账：已完成分批对账。
- 多实例缓存：已完成 1 Master + 2 Replica + 3 Sentinel。
