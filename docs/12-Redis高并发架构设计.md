# 12 · Redis 高并发架构设计

> 状态：已完成（2026-10-28）。代码、自动化测试、运行态验证和主从故障演练均已通过。

## 一、目标与边界

本阶段不是把 MySQL 替换成 Redis，而是把 Redis 明确分成两条访问链路：

1. 读链路：查票快照从 Replica 读取，尽量不占用 MySQL 连接。
2. 写链路：分段库存、UUID 幂等状态和版本号只写 Master。

MySQL 始终是订单、库存和支付的事实来源。Redis 故障、主从切换或数据丢失时，
查票和同步下单必须仍能走 MySQL 同步路径完成业务。

本阶段部署两套物理隔离的高可用 Redis：

- `redis-cache`：1 Master + 2 Replica + 3 Sentinel；
- `redis-stock`：1 Master + 2 Replica + 3 Sentinel；
- 每套 Sentinel quorum = 2，故障转移 majority = 2。

开发环境的 cache 节点使用 `172.18.0.40`～`172.18.0.45`，
stock 节点使用 `172.18.0.50`～`172.18.0.55`。
Master 停止后 Docker DNS 会同步移除容器名，因此 Sentinel 必须监控固定 IP，
否则会进入 `tilt` 而无法确认故障。Sentinel 配置写入各自的命名数据卷，
确保选举结果可持久化。

两套数据组拥有独立进程、数据卷、连接池和故障域。停止 cache 组只影响查票缓存，
不会影响动态库存和 UUID；停止 stock 组也不会清空查票对象。

## 二、读写路由

### 写连接

两套主写连接都使用独立 Jedis Sentinel Pool：

cache 组：
- `ticket:search:v*`；
- `ticket:search:version:*`。

stock 组：
- `ticket:stock:*` 分段库存；
- `ticket:order:idempotency:*` 下单 UUID 状态；
- `ticket:pay:idempotency:*` 支付 UUID 状态；
- 对账租约。

库存和幂等准入必须写 Master，不能由 Replica 承担。

### 读连接

应用另有 `ReplicaRedisReader`，定期向 Sentinel 查询两个 Replica，
按请求轮询建立读连接：

- `ticket:search:v*` 热点查票快照优先从两个 Replica 分担读取；
- 一个 Replica 不可用时尝试另一个，最后回退主写模板；
- Redis 全部不可用时回退 MySQL 的三阶段查票。

查票允许短时间陈旧，但库存变化会递增版本号。新版本 Key 尚未复制到 Replica 时
最多发生一次回源，不会返回旧版本结果。

## 三、单对象数组读模型

同一个查询结果只保存为一个 Redis String Value，不对每个车次、字段或席别拆成多个 Key。
对象内部使用平行数组，所有数组的同一小标对应同一车次：

```json
{
  "total": 2,
  "page": 1,
  "size": 10,
  "trainIds": [1, 2],
  "trainNos": ["G1001", "G1002"],
  "trainTypes": ["G", "G"],
  "fromStationNames": ["北京南", "北京南"],
  "toStationNames": ["上海虹桥", "上海虹桥"],
  "departTimes": ["08:00", "13:30"],
  "arriveTimes": ["12:00", "17:35"],
  "durationMinutes": [240, 245],
  "mileages": [1000, 1000],
  "remainCounts": [[20, 80, 500], [16, 70, 450]],
  "prices": [[1748, 933, 553], [1750, 940, 560]]
}
```

这样一次网络请求即可拿到整页结果。后端读取后按小标重组
`PageResult<TicketVO>`，前端接口格式保持不变。

## 三-A、车段车次静态索引

`TrainRouteIndex` 从启用车次的停站、分段和票价构建本地静态索引：

```text
fromStationId + toStationId
→ RouteBucket 单对象平行数组
```

数组保存 trainId、车次号、车型、起终站、起止段号、发到时刻、
运行时长、里程和分席别票价。当前数据构建结果为：

```text
296 个有向站对
840 条站对车次关系
构建耗时约 390ms
```

索引状态：

```text
NEW → REBUILDING → READY
                 ↘ UNAVAILABLE
```

重要规则：

- 静态数据变化时先标记 `REBUILDING`；
- 再递增 cache Redis 全局版本；
- 然后后台构建新索引；
- 构建完成后用 `AtomicReference` 原子替换；
- 构建失败时保持 `UNAVAILABLE` 并定时重试；
- 索引未就绪期间不读取 Redis 查票对象，直接走 MySQL 慢查询。

缓存未命中且索引 READY 时，只执行一次 `ticket_stock` 批量查询，
不再执行原来的 `countTrains + findTrainIds + findSeatRows` 三条联表查询。

## 四、UUID 幂等状态

Key：

```text
ticket:order:idempotency:{requestId}
ticket:pay:idempotency:{requestId}
```

值包含：

- `domain`：订单或支付；
- `userId`；
- `requestHash`：由业务字段和排序后的乘车人 id 计算；
- `ownerToken`：本次处理者令牌，防止旧线程覆盖新状态；
- `state`：`PROCESSING`、`SUCCESS`、`FAILED`；
- `resultNo`：订单号或支付流水号；
- `failReason`；
- `updatedAt`。

规则：

1. 使用 `SET NX` 或 Lua 原子抢锁，禁止“先 GET 再 SET”。
2. 相同请求摘要处于 `PROCESSING` 时短暂等待，不重复进入数据库。
3. 相同请求摘要处于 `SUCCESS` 时回查数据库并重放同一业务结果。
4. 不同用户或不同请求摘要使用同一 Key 时返回冲突。
5. 只有持有当前 `ownerToken` 的线程才能标记成功或失败。
6. Redis 状态是快速闸门；数据库唯一索引仍是最终兜底。

## 五、预热

启动后按配置延迟执行，并支持定时重复：

### 库存预热

- 扫描未来售票窗口的 `ticket_stock`；
- 批量 `SET NX` 写入分段数字库存；
- 只补缺失 Key，绝不覆盖运行中的预扣值；
- 数据库是最新事实时，由对账任务负责修正差异。

### 查票对象预热

- 从停站表找出有前后站关系的热门线路；
- 预热未来若干天、默认三种席别和第一页；
- 复用正式查票逻辑生成单对象数组快照；
- 限制线路数、天数和页大小，防止启动时打满 Redis 与 MySQL。

## 六、对账

`RedisMaintenanceJob` 调度 `RedisStockMaintenanceService` 分批比较 MySQL 和 Redis：

1. 按主键游标扫描未来窗口库存；
2. 读取 Master 上的分段库存；
3. 发现差异时检查该日期、车次是否存在 `order_request.status in (0,1,2)`；
4. 有活跃预扣则跳过，避免覆盖正在处理的请求；
5. 没有活跃预扣时以 MySQL 修正 Redis；
6. 修复后递增对应日期查票版本；
7. 同步下单提交后的 Redis 增量调整同样识别租约，对账期间暂缓；
8. 将扫描数、差异数、修复数和跳过数写入 `redis.log`。

## 七、故障降级

必须保留并验证以下路径：

| 故障 | 查票 | 同步下单 | 异步下单 |
| --- | --- | --- | --- |
| Replica 不可用 | 回退 Master/MySQL | 不受影响 | 不受影响 |
| Master 不可用 | Sentinel 切换或回退 MySQL | MySQL 事务兜底 | 明确失败，不冒险排队 |
| Redis 全部不可用 | 回退 MySQL | MySQL 唯一键和条件扣库存兜底 | 失败并提示稍后重试 |
| Redis 数据丢失 | 预热/对账重建 | 仍由 MySQL 保证正确 | 先重建库存再接受异步请求 |

同步下单的完整 MySQL 事务路径始终保留。Redis 只做快速幂等闸门，不代替
`ticket_order.request_id` 唯一索引，也不代替库存条件 UPDATE。

cache Redis 使用连续失败熔断。默认失败 3 次后暂停访问 15 秒，
期间直接使用 MySQL，避免每个请求等待 Redis 连接超时；冷却结束后自动探测恢复。

## 八、验证清单

- [x] cache/stock 两套物理 Redis 各 1 Master + 2 Replica + 3 Sentinel 正常启动。
- [x] Replica 可读，库存与 UUID 写入 Master。
- [x] 主节点故障后 Sentinel 选出新 Master，新拓扑已持久化。
- [x] 原 Master 恢复后自动以 Replica 身份重新加入。
- [x] cache 与 stock 的 Key 不再混写，故障域独立。
- [x] 车段车次索引可构建、失效、重建和原子替换。
- [x] 索引未就绪时绕过 Redis 对象，直接使用 MySQL 慢查询。
- [x] 缓存未命中时索引快路径只执行一次库存 SQL。
- [x] cache Redis 连续故障后熔断，冷却后自动恢复。
- [x] 前端下单和支付幂等键持久化到 `sessionStorage`。
- [x] UUID `SET NX` 并发只有一个 owner。
- [x] `PROCESSING` 阻止重复建单，`SUCCESS` 返回同一结果。
- [x] 请求摘要变化时返回幂等冲突。
- [x] 查票快照使用单对象数组并能无损还原前端结构。
- [x] 库存和热门查询对象预热只补缺失数据，并用跨轮游标继续扫描。
- [x] 对账修复差异且不会覆盖活跃预扣。
- [x] Redis 全部停止时查票和同步下单仍可用。
- [x] 一个 Replica 停止时仍可查票，恢复后重新加入复制链路。
- [x] 后端完整测试、两个前端构建和运行态健康检查通过。

## 九、验证结果

- 后端测试：63 个，59 通过，4 个手动性能基准跳过，0 失败。
- 实测首次启动预热：补建 5000 个库存 Key，预热 40 条线路共 240 个查票对象。
- 实测对账：扫描 2844 行，修复 702 个差异，活跃预扣跳过为 0。
- 真实 HTTP 登录、查票成功，返回 G1001 和正确区间余票。
- 停止 Master 后，三个 Sentinel 一致选出新 Master；后端自动恢复 Redis 写路径。
- 停止一个 Replica 后，HTTP 查票仍成功。
- 用户端和管理端生产构建均成功。
- 停止整个 cache 组后查票仍成功，熔断后延迟从秒级降到约 9ms，stock Redis 保持 `ok`。
- 停止 stock Master 后 Sentinel 自动切换，cache Redis 全程保持 `ok`。
