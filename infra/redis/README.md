# Redis 双物理集群开发环境

本目录启动两套完全独立的高可用 Redis：

| 数据组 | 职责 | 宿主机端口 |
| --- | --- | --- |
| `redis-cache` | 查票对象、全局版本、日期版本 | `6380`～`6382` |
| `redis-stock` | 分段库存、订单/支付 UUID、对账租约 | `6390`～`6392` |

每组都包含：

- 1 个 Master；
- 2 个 Replica；
- 3 个 Sentinel，quorum = 2。

两套数据组拥有独立进程、独立数据卷、独立连接池和独立故障域。

## 固定地址

| 节点 | 地址 |
| --- | --- |
| cache Master | `172.18.0.40:6379` |
| cache Replica | `172.18.0.41:6379`、`172.18.0.42:6379` |
| cache Sentinel | `172.18.0.43`～`172.18.0.45:26379` |
| stock Master | `172.18.0.50:6379` |
| stock Replica | `172.18.0.51:6379`、`172.18.0.52:6379` |
| stock Sentinel | `172.18.0.53`～`172.18.0.55:26379` |

上表是初始角色。故障转移后 Master 可能是组内任意一个节点，应用和运维
都应以 Sentinel 返回的地址为准。

Sentinel 必须监控固定 IP。若直接监控容器名，Master 停止后 Docker DNS
会同步消失，Sentinel 会进入 `tilt` 而无法判死。

Sentinel 的选举结果写入各自命名数据卷，宿主机模板保持只读。

## 启动

```powershell
docker compose -f infra/redis/docker-compose.yml up -d
```

旧单组服务已移除。首次替换时使用：

```powershell
docker compose -f infra/redis/docker-compose.yml down --remove-orphans
docker compose -f infra/redis/docker-compose.yml up -d
```

## 检查

```powershell
docker exec cache-master redis-cli INFO replication
docker exec stock-master redis-cli INFO replication

docker exec cache-sentinel-1 redis-cli -p 26379 `
  SENTINEL get-master-addr-by-name cachemaster
docker exec stock-sentinel-1 redis-cli -p 26379 `
  SENTINEL get-master-addr-by-name stockmaster
```

两个 Master 都应显示 2 个 online Replica；两个 Sentinel 应分别返回当前
cache/stock Master。

## 后端环境变量

```text
TICKET_CACHE_REDIS_MODE=sentinel
TICKET_CACHE_REDIS_MASTER=cachemaster
TICKET_CACHE_REDIS_SENTINEL_NODES=cache-sentinel-1:26379,cache-sentinel-2:26379,cache-sentinel-3:26379

TICKET_STOCK_REDIS_MODE=sentinel
TICKET_STOCK_REDIS_MASTER=stockmaster
TICKET_STOCK_REDIS_SENTINEL_NODES=stock-sentinel-1:26379,stock-sentinel-2:26379,stock-sentinel-3:26379

TICKET_REDIS_WARMUP_ENABLED=true
TICKET_REDIS_RECONCILE_ENABLED=true
```

## Key 边界

```text
cache 组：
ticket:search:v*
ticket:search:version:global
ticket:search:version:date:*

stock 组：
ticket:stock:*
ticket:stock-reconcile:*
ticket:order:idempotency:*
ticket:pay:idempotency:*
```

## 故障隔离

停止整个 cache 组：

```powershell
docker stop cache-master cache-replica-1 cache-replica-2 `
  cache-sentinel-1 cache-sentinel-2 cache-sentinel-3
```

预期：

- 查票仍返回；
- 前几次 Redis 探测可能等待超时；
- 熔断后直接走 MySQL 慢查询；
- stock Redis 和订单链路不受影响。

恢复：

```powershell
docker start cache-master cache-replica-1 cache-replica-2 `
  cache-sentinel-1 cache-sentinel-2 cache-sentinel-3
```

停止 stock Master 时，stock Sentinel 会自动选主；cache 组仍保持可用。

## 停止

```powershell
docker compose -f infra/redis/docker-compose.yml down
```

默认保留 12 个命名数据卷。显式添加 `-v` 会删除两套 Redis 的持久化数据。
