# Redis 高可用开发环境

本目录启动一组供本地学习使用的高可用 Redis：

- 1 个 Master：宿主机端口 `6380`；
- 2 个 Replica：宿主机端口 `6381`、`6382`；
- 3 个 Sentinel：宿主机端口 `26379`、`26380`、`26381`；
- Sentinel quorum 为 2，主节点故障时多数派同意才切换。

所有容器加入现有 `train-ticket-net`。Redis 节点使用固定实验地址
`172.18.0.20`～`172.18.0.25`；Master 停止后，即使 Docker DNS 不再解析
`redis-master`，Sentinel 仍能通过固定 IP 判定故障。

Sentinel 会把选举后学到的新拓扑写入各自的命名数据卷。
宿主机只读模板不会被 Redis 改写，避免 Windows 绑定挂载导致
`Permission denied` 和故障转移状态丢失。

## 启动

先确认 Docker 网络存在：

```powershell
docker network inspect train-ticket-net
```

然后启动：

```powershell
docker compose -f infra/redis/docker-compose.yml up -d
```

## 检查主从复制

```powershell
docker exec redis-master redis-cli INFO replication
docker exec redis-replica-1 redis-cli INFO replication
docker exec redis-replica-2 redis-cli INFO replication
```

Master 应显示两个 `slave0/slave1`，两个 Replica 应显示
`master_link_status:up`。

## 检查 Sentinel

```powershell
docker exec redis-sentinel-1 redis-cli -p 26379 `
  SENTINEL get-master-addr-by-name mymaster
docker exec redis-sentinel-1 redis-cli -p 26379 `
  SENTINEL replicas mymaster
```

三个 Sentinel 都应能报告当前 Master 和两个 Replica。

## 后端切换 Sentinel 模式

后端容器需要设置：

```text
TICKET_REDIS_MODE=sentinel
TICKET_REDIS_MASTER=mymaster
TICKET_REDIS_SENTINEL_NODES=redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379
TICKET_REDIS_WARMUP_ENABLED=true
TICKET_REDIS_RECONCILE_ENABLED=true
```

本机测试默认使用 standalone，连接 `localhost:6380`。这样 Maven 测试不依赖
Windows 宿主机能否直接访问 Docker 容器内网地址。

## 故障演练

```powershell
docker stop redis-replica-1
docker start redis-replica-1

docker stop redis-master
Start-Sleep -Seconds 8
docker exec redis-sentinel-1 redis-cli -p 26379 `
  SENTINEL get-master-addr-by-name mymaster
```

主节点下线后，地址应切换到一个原 Replica。恢复后新节点会以 Replica 身份重新加入。

## 停止

```powershell
docker compose -f infra/redis/docker-compose.yml down
```

默认保留三个数据卷，避免每次学习都丢失缓存。需要彻底清理时显式添加 `-v`；
该操作会删除这组 Redis 的持久化数据。
