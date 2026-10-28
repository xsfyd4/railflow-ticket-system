# 火车票购票系统（渐进式开发）

一个仿 12306 的在线购票系统，当作「从零到能上手项目」的练手工程。

项目的长期定位是**高并发、大数据量的铁路购票系统**。当前 1000 个车次和
284400 条库存用于扩大规模验证，但仍不代表全国真实容量设计。

它不是一个一次写完的项目，而是**按阶段慢慢加技术**：每个阶段结束都能跑起来、看得见效果，
再进入下一阶段。核心购票流程已经先用「前端 + 后端 + 数据库」跑通，
现在补上了 Redis 高可用读写分离、UUID 幂等、查票单对象读模型、
Redis Lua 分段库存预扣、预热与对账，以及 RabbitMQ 异步下单。

## 这个项目长什么样

两个 Vue 前端 + 一个 Spring Boot 后端都已经创建：

| 端 | 目录 | 使用者 | 主要功能 |
| --- | --- | --- | --- |
| 用户端 | `frontend-user/` | 普通乘客 | 注册登录 → 查车票 → 下单购票 → 我的订单 |
| 管理端（服务端） | `frontend-admin/` | 管理员 | 登录 → 车站/车次/停站管理 → 订单查看 → 数据统计 |
| 后端服务 | `backend/` | 上面两个端都调它 | 提供 REST 接口、业务逻辑、操作数据库 |

当前本地开发地址：

| 服务 | 地址 |
| --- | --- |
| 用户端 | http://127.0.0.1:5173 |
| 管理端 | http://127.0.0.1:5174 |
| 后端 API | http://127.0.0.1:8080/api |
| RabbitMQ 管理台 | http://127.0.0.1:15672 |

## 核心业务模型：分段库存

一趟车沿途停 N 个站，就把它切成 N-1 个「相邻站段」。票不是按「任意起点到任意终点」整段存，
而是**每一段各存一份库存**：

- 查 北京南 → 上海虹桥 有没有票：看中间每一段，取**余票最小值**
- 买 1 张 北京南 → 上海虹桥：中间每一段**各减 1**
- 退 1 张：中间每一段**各加 1**

这样做的好处是段数少（5 站车只要 4 条记录，而不是 10 条），
而且天然支持「座位复用」：A→B 段被占用了，B→C 段还能卖给别人。

详细推导见 [docs/02-数据库设计.md](docs/02-数据库设计.md)。

## 查票读模型

后端启动后构建本地“站对 → 车次数组”静态索引：

```text
cache Redis 查询对象
→ 未命中：本地车段车次索引
→ 一次 ticket_stock 批量查询
→ 组装对象写回 cache Redis
```

索引重建、损坏或 cache Redis 故障时自动回到原来的 MySQL 三阶段慢查询。
静态数据变更会先使索引失效和查票版本失效，再后台原子替换新索引。

## 技术选型

| 层次 | 选型 |
| --- | --- |
| 前端 | Vue 3 + Vite + Vue Router + Axios + 项目自研响应式样式 |
| 后端 | Java 17 编译目标 + Spring Boot 4.1.1 + MyBatis XML |
| 数据库 | MySQL 8.0+（本地验证使用 MySQL 9.7） |
| 缓存 | 本地车段车次索引 + 两套 Redis 7 高可用组：cache 与 stock 各 1 Master + 2 Replica + 3 Sentinel |
| 消息队列 | RabbitMQ 4 + Spring AMQP |
| 鉴权 | JWT（前期手写拦截器，不引入 Spring Security 全家桶） |
| 接口文档 | 待接入 OpenAPI |
| 构建 | Maven（后端）/ npm（前端） |

## 学习路线图

| 阶段 | 做完之后有什么 | 你会学到 |
| --- | --- | --- |
| 阶段 0 | 需求清单、原型草图、环境跑通 | 需求拆解、Git、Maven/npm |
| 阶段 1 | 后端能启动，返回统一格式的 JSON | Spring Boot 分层、统一返回/异常、Swagger |
| 阶段 2 | 两个端都能注册登录 | JWT、拦截器、密码加密、Vue 路由守卫 |
| 阶段 3 | 管理员能录车站、车次、停站、价格 | 表关系、级联 CRUD、事务、Element Plus 表单 |
| 阶段 4 | 能按日期生成库存、能查到余票 | 分段库存模型、复杂查询、批量插入 |
| 阶段 5 | 能下单、看订单、取消订单 | 事务与并发、防超卖、订单状态机 |
| 阶段 6 | 管理端能看订单和数据统计 | 多表统计 SQL、图表 |
| 阶段 7 | 两个端页面美化、联调、部署 | 前后端联调、Nginx、打包部署 |
| 阶段 8（已部分完成） | 用中间件扛住查询和下单峰值 | Redis 缓存、Lua 原子预扣、MQ 削峰、补偿与死信 |

## 文档

- [01 · 项目结构设计](docs/01-项目结构设计.md)
- [02 · 数据库设计](docs/02-数据库设计.md)
- [08 · 项目开发日志](docs/08-项目开发日志.md)
- [09 · 压测与并发测试报告](docs/09-压测报告.md)
- [10 · Redis 与 RabbitMQ 异步下单](docs/10-Redis与MQ异步下单.md)
- [11 · 项目优化想法清单](docs/11-优化想法清单.md)
- [12 · Redis 高并发架构设计](docs/12-Redis高并发架构设计.md)
- [分类工作日志](docs/logs/README.md)
- 建表 SQL：[sql/01-schema.sql](sql/01-schema.sql)
- 国内演示数据生成器：[scripts/gen_demo_data.py](scripts/gen_demo_data.py)

## 快速开始（建库）

全新数据库推荐执行完整结构和大规模国内演示数据：

```bash
mysql -u root -p < sql/01-schema.sql
mysql -u root -p --default-character-set=utf8mb4 < sql/03-demo-data-large.sql
```

`03-demo-data-large.sql` 当前包含：

- 110 个国内车站，覆盖 32 个省级地区、87 个城市。
- 恰好 1000 个国内车次：600 个 G、120 个 D、160 个 K、80 个 T、40 个 Z。
- 4160 条停站、3160 个分段、9480 条分段票价。
- 从执行脚本第二天开始，共 30 天、284400 条分段库存。

演示账号统一密码 `123456`：

| 端 | 用户名 | 角色 |
| --- | --- | --- |
| 用户端 | `zhangsan` | 普通用户，已有一个乘车人 |
| 用户端 | `lisi` | 普通用户，已有一个乘车人 |
| 管理端 | `admin` | 超级管理员 |

重新生成 `03` 数据：

```bash
python -B scripts/gen_demo_data.py
```

## 启动前端

```powershell
cd frontend-user
npm install
npm run dev
```

另开终端：

```powershell
cd frontend-admin
npm install
npm run dev
```

两个 Vite 应用都会把 `/api` 代理到 `http://localhost:8080`。

用户端和管理端都使用演示账号：

```text
用户端：zhangsan / 123456
管理端：admin / 123456
```

## 后端运行

复制本地配置模板，并填写自己的 MySQL 密码：

```powershell
Copy-Item `
  backend/src/main/resources/application-local.yml.example `
  backend/src/main/resources/application-local.yml
```

`application-local.yml` 已被 Git 忽略。也可以使用
`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 和
`TICKET_JWT_SECRET` 环境变量覆盖默认配置。

配置完成后，在 IDEA 中启动 `TicketApplication` 即可。

当前 Codex 工具环境中的 JDK 23 无法为 Tomcat 建立 Windows 内部 loopback pipe，
因此本地联调也支持使用 Docker 中的 Linux JDK 启动后端。容器网络
`train-ticket-net` 已把后端、Redis、RabbitMQ 连接在一起，MySQL 通过
`host.docker.internal:3306` 连接。

## Redis 高可用

启动或重建开发环境：

```powershell
docker compose -f infra/redis/docker-compose.yml up -d
```

该环境包含两套物理隔离的高可用组：

| 数据组 | 职责 | Master | Replica |
| --- | --- | --- | --- |
| `cache` | 查票对象、版本号 | `172.18.0.40` | `172.18.0.41/42` |
| `stock` | 库存、UUID、对账租约 | `172.18.0.50` | `172.18.0.51/52` |

每组还有三个独立 Sentinel。宿主机端口：

表中是初始角色；发生故障转移后，实际 Master 以 Sentinel 查询结果为准。

```text
cache 数据：6380、6381、6382
cache 哨兵：26379、26380、26381
stock 数据：6390、6391、6392
stock 哨兵：26389、26390、26391
```

后端容器使用：

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

详细启动、复制检查、故障演练和配置持久化说明见
[`infra/redis/README.md`](infra/redis/README.md)。

`01-schema.sql` 已经包含 `pay_log`。`04-pay-log.sql` 只用于已经按旧版
`01-schema.sql` 建过库、又不想重建数据库的增量迁移，全新安装不需要单独执行。

`pay_log` 是运行时交易流水，不做演示初始化。只有用户发起真实或模拟支付后才会产生记录。

已经用旧版脚本建过库时，按顺序执行两个增量迁移：

```bash
mysql -u root -p train_ticket < sql/05-order-request.sql
mysql -u root -p train_ticket < sql/06-order-request-reservation.sql
```

异步下单接口：

```http
POST /api/user/orders/async
Authorization: Bearer <token>
Idempotency-Key: <客户端生成的唯一ID>
Content-Type: application/json
```

查询处理结果：

```http
GET /api/user/orders/async/{requestId}
Authorization: Bearer <token>
```

原有同步接口 `POST /api/user/orders` 继续保留，便于对照学习。

## 综合压测

混合读写压测默认按 90% 查票、5% 下单后取消、3% 订单列表和
2% 支付幂等执行：

```powershell
python -B scripts/mixed_load_test.py `
  --processes 4 --workers-per-process 16 `
  --duration 30 --travel-date 2026-10-29 `
  --output docs/load-test-mixed-64w-30s.json
```

RabbitMQ 异步下单完整链路压测：

```powershell
python -B scripts/async_order_test.py `
  --count 200 --concurrency 32 --travel-date 2026-10-29 `
  --output docs/load-test-async-200.json
```
