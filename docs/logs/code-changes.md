# 代码变更日志

## 2026-10-28：GitHub 提交前清理

**配置安全**

- 数据库、RabbitMQ 和 JWT 密钥改为环境变量覆盖。
- 增加 `application-local.yml.example`，本地密码放在被 Git 忽略的
  `application-local.yml` 中。
- README 增加本地配置模板和环境变量说明。

**仓库清理**

- 修正 `.gitignore`，只忽略运行日志目录，使 `docs/logs` 可以提交。
- 忽略 Maven 原始 JAR、Python 缓存、stdout 和 stderr。
- 删除未引用的中间压测结果、smoke 结果和压测控制台输出。
- 清理已实现业务代码中的 TODO 教学注释，整理车次和车站服务格式。
- 修正车次分页参数小于 10 时被强制放大为 10 的问题。

**验证**

- 完整后端测试 70 项：66 通过、4 个手工性能基准跳过、0 失败。

## 2026-10-28：查票缓存雪崩、穿透与击穿防护

**修改**

- `TicketSearchCache` 增加 TTL 随机抖动、空结果短 TTL、Redis 重建锁和 owner Lua 释放。
- `TicketSearchService` 增加索引前置空结果判断、抢锁回源和指数退避等待。
- 新增锁竞争、缓存等待命中、不存在站对直接返回等测试。
- 配置增加 jitter、空结果 TTL、锁 TTL 和退避参数。

**验证**

- 完整后端 70 个测试：66 通过、4 跳过、0 失败。
- 清空 cache Redis 后 64 并发冷查询：39602 请求、0 失败、7896.23 QPS、
  P99 22.45ms。
- HTTP 线程查票 SQL 仅 1 次，无残留重建锁。
- 热缓存 128 并发持续 10 秒：13536.70 QPS，0 失败，P99 21.58ms。

## 2026-10-28：综合读写与异步下单压测工具

**新增**

- `scripts/mixed_load_test.py`：90% 查票、5% 下单后取消、
  3% 订单列表、2% 支付幂等。
- `scripts/async_order_test.py`：异步提交、状态轮询、MQ 消费、订单落库和取消回补。
- README 增加两类压测的复现命令。

**验证结果**

- 64 并发、30 秒混合流量：95092 次逻辑操作全部成功，0 失败，
  3161.07 操作/秒，P99 123.44ms。
- 200 笔、32 并发异步订单：全部成功，135.04 单/秒，
  端到端 P99 337.29ms。
- 测试结束后无待支付订单、无 RabbitMQ 积压，库存恢复到测试前。

## 2026-10-28：演示数据扩充到 1000 车次

**修改**

- `scripts/gen_demo_data.py` 把 50 条基准线路扩展为正反两个方向。
- 每个方向生成 10 个时段，合计恰好 1000 个车次。
- 保留 110 个车站和五种车型，重新生成停站、分段、票价和 30 天库存。
- 用户端登录页、查票页文案同步更新为 1000 车次。
- README、当前架构说明和优化清单同步更新数据规模。

**生成结果**

```text
车站 110 / 车次 1000 / 停站 4160
分段 3160 / 票价 9480 / 库存 284400
```

**验证**

- 数据库计数与生成器输出一致。
- G/D/K/T/Z 数量为 600/120/160/80/40，车次号无重复。
- 索引构建成功：538 个有向站对、8400 条站对车次关系、耗时 526ms。
- 真实登录并查询 `北京南 → 上海虹桥`，返回 10 个车次。
- 128 并发、10 秒热点查票：11900.61 QPS，119324 请求全部成功，
  P99 33.13ms。
- 重启后 128 并发、60 秒热点查票：12445.95 QPS，747720 请求全部成功，
  P99 22.69ms。
- 查票 SQL、车段索引和订单服务三组集成测试共 18 个，0 失败。

**后续**

- 历史压测结果仍属于 100 车次数据，不能作为新数据规模的结论。
- 需要在 1000 车次数据上重新执行热缓存、冷查询和故障降级压测。

## 2026-10-28：索引版本与 Redis 全停降级复测

**基线**

- 当前索引 READY、cache/stock 双 Redis 正常。
- 128 并发持续 30 秒：12988.16 QPS，390539 请求成功，P99 22.69ms。
- 相同条件 128 并发持续 60 秒：13217.86 QPS，794085 请求成功，P99 19.20ms。
- 改造前同条件为 11538.40 QPS、P99 19.37ms；QPS 提升约 14.6%。

**cache Redis 全停**

- 16 并发：2822.29 QPS，P99 13.22ms。
- 32 并发：3722.71 QPS，P99 14.66ms。
- 64 并发：2915.69 QPS，P99 40.60ms。
- 128 并发：3430.77 QPS，P99 95.45ms。
- 256 并发：3515.00 QPS，P99 171.02ms。
- 所有轮次 0 失败。

**数据库保护结论**

- cache 故障时 MySQL 约 6～6.6 核。
- Hikari 连接保持 20 个，没有全部占满。
- MySQL `Threads_running` 约 5～12。
- 当前数据规模不会因 cache Redis 全停而打爆 MySQL。
- 高并发下主要表现为 P99 延迟增长。

**恢复**

- cache Redis 恢复后健康检查自动回到 `ok`。
- stock Redis 和 RabbitMQ 在 cache 故障期间始终正常。

## 2026-10-28：清理废弃文件和重复产物

**删除**

- 旧版 5 站初始化脚本 `sql/02-init-data.sql`。
- 临时管理员 SQL `sql/3.sql`。
- 一次性工具 `JmxPoolTuner.java`、`PasswordHashPrinter.java`。
- 早期随手笔记 `docs/logs/我简直神了.md`。
- 未被报告引用的重复中间压测 JSON，保留历史基线、最终 60 秒结果和
  `/ping` 峰值原始结果。
- 根目录和 `backend/` 下的运行日志、旧前端调试截图。
- 用户端、管理端 `dist` 构建产物。
- `backend/target` 中测试报告、测试类、主类、生成目录和原始 JAR，
  保留当前容器正在挂载的最终可执行 JAR。

**保留**

- 正式建库和增量迁移 SQL。
- 单进程历史压测脚本、多进程压测器和报告引用的原始 JSON。
- 当前运行 JAR、全部源码、测试、文档和基础设施配置。

**验证**

- 已确认没有指向被删除文件的残留引用。
- 清理后正式后端健康检查仍为 stock Redis、cache Redis、RabbitMQ 全部正常。

## 2026-10-28：车段车次索引、索引生命周期与物理 Redis 拆分

**新增**

- `TrainRouteIndex`、`RouteBucket`：本地车段车次单对象平行数组索引。
- `TrainRouteIndexMapper`：批量读取启用车次停站和票价。
- `IndexedTicketSearchService`：索引查车次 + 一次库存 SQL 的快查询。
- `RedisCircuitBreaker`：cache Redis 连续失败熔断，默认 3 次失败、15 秒冷却。
- cache/stock 两套物理 Redis 配置、连接工厂和主写模板。
- 索引生命周期、快查询、熔断和失效重建测试。

**查票分流**

```text
索引 READY + cache Redis 可用
→ Redis 对象命中则返回
→ 未命中走本地索引
→ 一次 ticket_stock 批量查询
→ 组装对象写 cache Redis

索引 REBUILDING/UNAVAILABLE 或 cache Redis 熔断
→ 原 MySQL 三阶段慢查询
```

**索引更新**

- 车次、停站、分段、票价或站名变化后，事务提交时先标记索引
  `REBUILDING`。
- 然后递增 cache Redis 全局版本和本地版本。
- 后台线程构建新索引，完成后用 `AtomicReference` 原子替换。
- 构建失败进入 `UNAVAILABLE`，定时重试，期间持续使用慢查询。

**物理 Redis 拆分**

- `redis-cache`：查票对象和版本号。
- `redis-stock`：分段库存、订单/支付 UUID、对账租约。
- 两套各自 1 Master + 2 Replica + 3 Sentinel，共 12 个容器。
- cache 与 stock 使用不同固定地址、数据卷、连接池和故障域。

**前端**

- 下单幂等键按“用户 + 完整订单摘要”写入 `sessionStorage`，刷新后同一订单复用。
- 支付幂等键按“用户 + 订单号”写入 `sessionStorage`，支付成功后清除。

**验证**

- 后端测试 63 个：59 通过，4 个手动性能基准跳过，0 失败。
- 索引运行态：296 个站对、840 条站对车次关系，构建约 390ms。
- 未预热查票的数据库日志只出现一次 `findForSearch`。
- cache Redis 整组停止：查票仍成功；熔断后约 9ms。
- cache 故障期间 stock Redis 保持 `ok`；恢复后 cache 自动恢复。
- stock Master 停止后 Sentinel 自动选主，cache Redis 全程 `ok`。
- 用户端和管理端生产构建成功。

## 2026-10-28：多进程真实 HTTP 极限压测

**目标**

- 绕开单个 Python 进程的 GIL，测量 Redis 高可用版本的真实 HTTP 上限。
- 区分纯 HTTP、Redis 热点查票和 MySQL 回源三档吞吐。
- 通过资源采样定位瓶颈，避免只堆并发不分析。

**修改文件**

- 新增 `scripts/max_load_test.py`。
- 更新 `docs/09-压测报告.md`。
- 新增 `docs/load-test-mp-*.json`、`docs/load-test-ping-*.json`、
  `docs/load-test-db-*.json` 和 60 秒持续压测结果。

**压测器行为**

- 多进程隔离 Python GIL；
- 每进程多线程复用 HTTP/1.1 长连接；
- 所有 TCP 连接建立后统一发令；
- 每请求只检查 HTTP 状态和业务 `code=200`，避免压测端解析完整 JSON；
- 汇总总 QPS、失败数和平均/P50/P95/P99/最大延迟。

**结果**

- 纯 HTTP `/ping` 峰值：19708.61 QPS / 256 并发 / 0 失败。
- Redis 热点查票短时峰值：12760.49 QPS / 256 并发 / 0 失败。
- Redis 热点查票 128 并发持续 60 秒：11538.40 QPS / 693560 请求 / 0 失败。
- MySQL 回源查票：约 3432 QPS 峰值 / 64 并发 / 0 失败。
- Hikari 20 调到 40 没有稳定收益，数据库连接不是主要瓶颈。
- 256 并发时整机 CPU 99%，后端约 10.9 核，Redis 三节点仍未跑满。

**结论**

- 本机短时峰值约 12.8k 热点查票 QPS，稳定工作点约 11.5k。
- 512 并发时 QPS 回落、P99 显着上升，不应作为稳定配置。
- 压测端与服务端共享同一台 32 线程电脑，是继续测高的主要限制。

## 2026-10-27～28：Redis 高可用、UUID 幂等、读模型与对账

**目标**

- 接入 1 个 Redis Master、2 个 Replica 和 3 个 Sentinel。
- 查票快照从 Replica 读，库存、版本和 UUID 只写 Master。
- 增加订单/支付 UUID 快状态，保存请求摘要和 ownerToken。
- 把完整查票结果改成一个对象内的平行数组。
- 增加库存/热门查询预热与 MySQL/Redis 库存对账。
- 保留 MySQL 同步查票和同步下单降级路径。

**主要修改**

- 新增 `TicketRedisProperties`、`TicketRedisConfig`：主写使用 Jedis Sentinel，
  本地测试使用 standalone。
- 新增 `ReplicaRedisReader`：定期向 Sentinel 查询两个副本，按请求轮询读取；
  一个副本故障时尝试另一个，读取失败后由查票服务回退主节点和 MySQL。
- 新增 `TicketSearchSnapshot`：把整页查票结果保存成单对象平行数组。
- 新增 `RedisIdempotencyStore`、`IdempotencyHash`：订单和支付共用 UUID 快状态，
  保存用户、请求摘要、ownerToken、状态、结果号和失败原因。
- 同步下单、异步下单和支付接入 UUID 闸门；数据库唯一键仍是最终兜底。
- 补同步下单重放时的完整 `passengerIds` 比较，不再只比较人数。
- 新增 `RedisStockMaintenanceService`、`RedisMaintenanceJob`：
  库存预热、热门查票对象预热、MySQL/Redis 分批对账。
- 预热和对账使用跨轮主键游标，超过单轮上限后下一轮继续，不会永久重复扫描前 5000 行。
- 对账为日期+车次加短租约；预扣 Lua 发现租约时暂缓，避免覆盖正在处理的库存。
- 同步下单提交后的库存增量同步也识别租约，避免对账旧快照与增量调整交叉写坏数字库存。
- 新增 `infra/redis`：1 Master、2 Replica、3 Sentinel，固定实验 IP，
  Sentinel 学习结果写入命名数据卷。
- 新增 UUID、数组读模型、Replica 路由、预热对账和 Redis 全停同步下单测试。
- 新增独立 `src/test/resources/application.properties`，自动化测试固定使用原有
  `localhost:6379`，避免高可用故障演练后的主从角色污染测试结果。

**发现并修复**

- Windows/JDK 23 下 Lettuce/NIO 无法建立 loopback selector，主写改回 Jedis；
  两个 Replica 由专用 Jedis 轮询器读取。
- Sentinel 原配置用容器名监控 Master，Master 停止后 DNS 消失，Sentinel 进入
  `tilt`，无法判死。已改为固定 IP。
- Sentinel 配置文件是只读绑定挂载，选举结果无法保存。已改为只读模板 +
  可写命名数据卷。
- 支付测试固定幂等键在数据库清理后可能留下 Redis 旧 `SUCCESS`。数据库确认无结果时
  现在可回收非 `PROCESSING` 的旧终态，仍在处理中的状态禁止覆盖。
- 预热/对账游标原先每轮从 0 开始，已改为跨轮继续。

**验证**

- `mvn -B test`：57 个测试，53 通过，4 个手动性能基准跳过，0 失败。
- 用户端 `npm run build`：成功。
- 管理端 `npm run build`：成功。
- 真实 HTTP 登录和查票成功，返回 G1001。
- 启动预热：5000 个库存 Key、40 条线路共 240 个查票对象。
- 对账：扫描 2844 行，修复 702 个差异。
- 停一个 Replica：查票仍成功。
- 停 Master：Sentinel 自动选主，后端 Redis 健康检查恢复；原 Master 重启后
  自动成为新 Master 的副本。

**运行状态**

- 后端容器 `train-ticket-backend` 使用三个 Sentinel 地址启动。
- 旧 standalone 容器保留为 `train-ticket-backend-standalone-backup`，处于停止状态。
- 当前 Redis 拓扑回到供测试使用的 Master `172.18.0.20`、Replica
  `172.18.0.21/22`，三个 Sentinel 一致。

## 2026-10-27：汇总近期开发与架构决策

- 在 `docs/08-项目开发日志.md` 新增第 21 条近期开发总览。
- 汇总用户端、管理端、Redis、RabbitMQ、订单幂等、库存、索引、测试和运行环境。
- 区分“已实现能力”和“有价值但未落地的想法”。
- 同步修正长期清单中的 Redis 拆分状态，明确高并发目标下应物理拆分 `redis-cache` 与 `redis-stock`。

## 2026-10-27：记录高并发大数据量长期定位

- 在 `docs/11-优化想法清单.md` 增加项目长期约束。
- 明确当前 100 车次数据只是功能验证数据。
- 后续架构评审默认考虑高峰查询、库存争抢、连接池、Redis 热点、MQ 积压和 MySQL 写入上限。
- 明确物理拆分 `redis-cache` 与 `redis-stock` 是后续高并发方向，逻辑库拆分不算资源隔离。

## 2026-10-26：修复新增车次流程与结构修改保护

**现象**

管理端新增车次目前只有车次号、始发站、终到站等基础字段。创建后没有停站、
分段、票价和库存，车次默认却是启用状态。

**涉及位置**

- `backend/src/main/java/com/ticket/module/train/impl/TrainServiceImpl.java`
- `backend/src/main/java/com/ticket/module/train/dto/TrainSaveDTO.java`
- `backend/src/main/java/com/ticket/module/train/AdminTrainStopController.java`
- `frontend-admin/src/views/TrainsView.vue`

**原因**

- 后端将车次、停站、票价拆成三个接口。
- 管理端只实现了车次基本信息的新增/编辑。
- 停站接口会生成分段，但前端没有编辑停站和票价的入口。
- 新车默认 `status=1`，没有“待配置”状态。

**影响**

新增车次不可查询、不可下单、不能生成库存，管理员只能通过直接调用接口继续配置。

**关联缺陷**

车次分页 offset 使用 `(page - 1) * 10`，没有乘实际 `size`。

**修复**

- 新增 `TrainFullCreateDTO` 和 `TrainFullCreateService`。
- 新增 `POST /api/admin/trains/full`，事务内创建车次、停站、分段和票价。
- 管理端新增车次改为三步向导。
- 线路弹窗增加停站、票价查看和编辑。
- 有停站时，车次基本信息只能改车次号、车型、状态和备注。
- 有库存时禁止改停站；提供受控清空库存接口。
- 有订单时禁止清库存和改停站。
- 新增 Redis 车次库存 Key 的 `SCAN` 清理。
- 修复车次分页 offset 写死 10 的问题。
- `TrainService.create/update` 补上事务注解。
- 修复错误的 SnakeYAML `DuplicateKeyException` 导入。

**验证**

- 新增车次专项测试 4 个全部通过。
- 完整后端测试 48 个：44 通过，4 个手动基准跳过，0 失败。
- 管理端真实 API 完整创建 `G9902`：3 停站、2 分段、6 票价，随后成功删除。

## 2026-10-26：创建用户端和管理端 Vue 应用

**新增**

- `frontend-user/`：Vue 3 + Vite + Vue Router + Axios。
- `frontend-admin/`：Vue 3 + Vite + Vue Router + Axios。
- 两套应用均通过 Vite `/api` 代理连接后端 8080。

**用户端功能**

- 登录、注册；
- 车站联想和车票查询；
- 席别价格和分段库存展示；
- 乘车人选择与常用乘车人管理；
- 带 `Idempotency-Key` 的同步下单；
- 订单列表、订单详情、模拟支付、取消和部分/全部退票。

**管理端功能**

- 管理员登录；
- 车站分页、新增、编辑、删除；
- 车次分页、新增、编辑、删除；
- 查看线路停站、分段和票价；
- 按日期和车次查看分段库存；
- 批量生成库存和调整余票。

**运行地址**

```text
用户端：http://127.0.0.1:5173
管理端：http://127.0.0.1:5174
后端：http://127.0.0.1:8080/api
```

**验证**

- 用户端和管理端生产构建成功。
- 通过 5173 代理登录 `zhangsan`、查询北京南到上海虹桥、读取乘车人和订单成功。
- 通过 5174 代理登录 `admin`、读取 110 个车站、100 个车次和 12 条车次库存成功。
- 后端 MySQL、Redis、RabbitMQ 健康检查全部通过。

## 2026-10-24：下单 SQL 批量化与长期优化清单

**目标**

- 减少多乘客下单的数据库往返次数。
- 固定一人和多人下单的 SQL 数量。
- 把历史讨论形成的设计思路集中成长期文件。

**修改**

- 新增 `PassengerMapper.findByIdsAndUserId`，一次 IN 查询校验全部乘车人及归属。
- `OrderServiceImpl` 用一次乘车人查询替代 N 次单条查询。
- `OrderAsyncServiceImpl` 的提交前校验也改为一次批量查询。
- `OrderMapper` 用 `batchInsertItems` 一次插入全部订单明细。
- 同步正式下单从 `2N + 4` 条 SQL 降为固定 6 条：
  乘车人 1、路线 1、票价 1、库存 1、订单 1、订单明细 1。
- 新增 `docs/11-优化想法清单.md`，作为后续设计想法的长期入口。
- 增加“混合自己的乘车人与别人的乘车人”回归测试。

**验证**

- 订单、支付和异步下单定向测试共 24 个全部通过。
- 完整测试共 38 个：34 个通过，4 个手动性能基准跳过，0 失败。
- 批量查询仍同时携带 `user_id`，不能借批量查询绕过权限隔离。
- 两位乘客的订单仍生成两条明细，原有价格、库存和退票测试保持通过。

## 2026-10-24：演示数据生成器升级为国内 100 车次

**修改文件**

- `scripts/gen_demo_data.py`
- `scripts/PasswordHashPrinter.java`
- `sql/03-demo-data-large.sql`
- `backend/src/test/**`

**行为变化**

- 车站从 102 增至 110，新增西宁、银川、乌鲁木齐、拉萨、香港西九龙等车站。
- 线路从 20 条增至 50 条，每条生成 2 个车次，合计恰好 100 个。
- 车型分布：G=60、D=12、K=16、T=8、Z=4。
- 速度和价格倍率按车型区分，不再全部使用 G 型参数。
- 自动生成执行日期起未来 30 天的分段库存。
- 自动生成 `admin`、`zhangsan`、`lisi` 三个演示账号，密码均为 `123456`。
- 测试中的 G1001 终点站恢复为当前种子数据的 `station_id=27`。

**验证**

```text
Tests run: 37, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

4 个跳过项均为手动性能基准。

## 2026-10-24：Redis 分段库存 + RabbitMQ 异步下单

**目标**

- Redis 参与下单库存预扣，而不只缓存查票结果。
- RabbitMQ 承接异步下单，原有同步接口保留。
- 重复提交、重复投递和失败补偿都具备可恢复状态。

**新增**

- `AsyncOrderController`
- `OrderAsyncServiceImpl`
- `OrderRequestTxService`
- `OrderRequestFailureService`
- `AsyncOrderPublisher`
- `OrderCreateConsumer`
- `OrderRequestRecoveryJob`
- `TicketStockCache`
- `OrderRequest`、Mapper、XML、VO、消息 DTO
- `RabbitOrderConfig`
- `OrderAsyncIntegrationTest`
- `TicketStockCacheIntegrationTest`

**修改**

- `OrderServiceImpl` 拆出 `createForUser`，供没有 HTTP ThreadLocal 的消费者调用。
- 同步下单成功后递减 Redis 数字库存。
- 取消、退票、超时取消后递增 Redis 数字库存。
- 管理员调整库存后在事务提交后从 MySQL 刷新 Redis。
- `application.yml` 增加异步消费者、恢复任务和数字库存配置。
- `logback-spring.xml` 将 `com.ticket.mq` 输出到 `mq.log`。

**验证**

```powershell
cd backend
mvn -o -B test
```

结果：

```text
Tests run: 37, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```

4 个跳过项都是需要显式 `-Dbenchmark=true` 的手动性能基准。

**回滚**

- 不调用异步接口即可继续使用同步下单。
- 设置 `ticket.cache.stock-enabled=false` 后异步接口会明确返回排队服务不可用，
  同步下单仍由 MySQL 条件更新防超卖。
- 回滚代码时不要删除 `order_request` 历史数据，先导出排障。

## 2026-10-24：建立分类日志和运行日志

**目标**

- 建立持续维护的代码、数据库、Redis、MQ 四类工作日志。
- 将后端运行日志拆分到独立文件。
- 避免 MyBatis SQL 日志全部输出到控制台。

**修改文件**

- `backend/src/main/resources/logback-spring.xml`
- `backend/src/main/resources/application.yml`
- `docs/logs/README.md`
- `docs/logs/code-changes.md`
- `docs/logs/database-changes.md`
- `docs/logs/redis-operations.md`
- `docs/logs/mq-operations.md`

**行为变化**

- 业务日志写入 `logs/application.log`。
- Mapper SQL 写入 `logs/database.log`，默认保留 30 天。
- Redis 与 MQ 日志预留独立 Appender。
- 日志按日期、大小滚动并压缩。
- HikariCP 最大连接数从 10 调整为 20，最小空闲连接从 2 调整为 5。

**验证**

- Maven 编译。
- 数据库访问层 64 并发对照：
  - 池 10：9201.52 QPS，P95 42.41ms。
  - 池 20：15557.34 QPS，P95 20.97ms。
- Spring MVC 进程内 64 并发对照：
  - 池 10：1716.59 QPS，P50 36.17ms。
  - 池 20：3102.65 QPS，P50 17.64ms。
- Spring MVC 池 20、128 并发：
  - QPS 2477.24，P50 48.94ms，P95 80.30ms。
  - 128 并发低于 64 并发，说明继续增加请求线程会恶化排队。
- 结论：扩池有明显收益；真实 HTTP 需重启后复测。

**风险与回滚**

- 连接池扩大后需要继续观察 MySQL CPU、锁等待和连接数。
- 如果 20 个连接没有收益，可回滚为 10。

## 2026-10-24：增加连接池对照基准

**目标**

- 单独验证 HikariCP 从 10 调到 20 的收益。
- 区分数据库访问层与 Spring MVC 应用层上限。

**修改文件**

- `backend/src/test/java/com/ticket/performance/DatabasePoolBenchmarkTest.java`
- `backend/src/test/java/com/ticket/performance/HttpPipelineBenchmarkTest.java`

**执行方式**

```bash
cd backend
mvn -Dbenchmark=true -Dtest=DatabasePoolBenchmarkTest test
mvn -Dbenchmark=true -Dtest=HttpPipelineBenchmarkTest test
```

**保护**

- 两个基准默认不参与普通 `mvn test`。
- 只执行只读查票 SQL，不修改库存和订单。

## 2026-10-24：只读查票事务实验及回滚

**尝试**

- 给 `TicketServiceImpl.search()` 添加 `@Transactional(readOnly = true)`。
- 目标是一次持有连接并执行 `countTrains`、`findTrainIds`、`findSeatRows`。

**对照结果**

- 单轮测试波动较大，随后增加 `ReadTransactionBenchmarkTest`。
- 64 并发、池 20、每个场景并发预热 1 秒、交替运行 3 轮。
- 无事务后两轮稳定 QPS：5970.11、5897.70。
- 只读事务后两轮稳定 QPS：5659.33、5425.73。
- 预热后无事务吞吐约高 7%；只读事务中位延迟更低。

**结论**

- 当前 20 个连接面对 64 个请求时，长期持有连接会降低复用效率。
- 撤回 `TicketServiceImpl` 外层事务。
- 后续 Redis 未命中数据库回源时，再在独立 Service 中评估短事务。

**修改文件**

- `backend/src/main/java/com/ticket/module/ticket/impl/TicketServiceImpl.java`
- `backend/src/test/java/com/ticket/performance/ReadTransactionBenchmarkTest.java`

## 2026-10-24：接入 Redis 查票缓存

**目标**

- 缓存完整查票分页结果，缓存命中时完全不访问 MySQL。
- 数据变化后自动失效。
- Redis 故障时能够降级。

**修改文件**

- `TicketSearchCache.java`
- `TicketCacheInvalidator.java`
- `TicketServiceImpl.java`
- `TicketSeatVO.java`
- 订单、库存、车次、停站和车站 Service
- `application.yml`
- `logback-spring.xml`
- `pom.xml`

**实现**

- 缓存 Key 包含全局版本、日期版本和完整查询参数。
- TTL 为 10 秒。
- 库存变化递增日期版本。
- 基础配置变化递增全局版本。
- 事务操作在 `afterCommit` 中失效缓存。
- 缓存异常时回源 MySQL，不阻断业务。
- Redis 客户端从 Lettuce 切换为 Jedis。

**验证**

- 缓存命中集成测试通过。
- 下单、取消、退票和库存调整后失效测试通过。
- Redis 端口不可用时降级测试通过。
- 64 并发缓存命中约 4902 QPS。
- 128 并发缓存命中约 5829 QPS。

## 2026-10-24：测量停站表和线路 HashMap 构建成本

**新增**

- `backend/src/test/java/com/ticket/performance/StaticRouteIndexBenchmarkTest.java`

**测试内容**

- 查询全部 132 条停站。
- 构建 82 个方向性车站组合。
- 构建 182 条车次路线关系。

**结果**

- MySQL 查询平均 0.795ms，P95 1.012ms。
- 纯 Java HashMap 构建平均 0.049ms，P95 0.066ms。
- 查询加构建平均 0.843ms，P95 1.075ms。

**结论**

- Java 构建占总时间约 6%。
- 当前数据下主要延迟来自数据库访问。
- 线路索引应在启动或配置变化时构建，不应每个请求重建。

## 2026-10-24：增加 16 账号并发抢票测试

**目标**

- 将最后一张票并发测试从 2 个线程提高到 16 个独立账号。

**修改文件**

- `backend/src/test/java/com/ticket/module/order/impl/OrderServiceImplIntegrationTest.java`

**验证**

- 16 个请求只有 1 个成功。
- 最终库存为 0。
- 订单表只有 1 笔成功订单。

**结果**

- 全部后端测试通过。

## 2026-10-24：建立 HTTP 压测基线

**目标**

- 在未使用 Redis/MQ 的情况下测量查票接口上限。

**修改文件**

- `scripts/load_test.py`
- `docs/09-压测报告.md`
- `docs/load-test-*.json`

**验证**

- 1、8、16、32、64 并发全部请求成功。
- 16 并发约 2534 QPS，P95 约 10ms。
- 32/64 并发进入吞吐平台，P95 明显增加。

**瓶颈证据**

- 64 并发线程栈中 51/65 个 HTTP 线程等待 Hikari 连接。
- Java 约占整机 CPU 26.15%，MySQL 约占 10.57%。
- 首要瓶颈是数据库连接池排队，不是 CPU 跑满。

**回滚**

- 删除压测脚本和报告即可，不影响业务代码。
