# 数据库操作日志

## 2026-10-28：综合读写压测产生的订单与库存验证

**写入**

- 混合压测执行 4759 次同步下单并立即取消。
- 异步链路压测执行 200 次异步下单并取消。
- 支付幂等测试只重复调用一笔原已支付订单，不新增支付流水。

**验证**

```text
ticket_order: 5221 行，待支付 0，已取消 5219
order_request: 210 行，成功 210，失败 0
10-29 10 个测试车次二等座余票总量：测试前后均为 23649
```

**结论**

- 下单后取消完整回补了 MySQL 与 Redis 库存。
- 没有遗留待支付订单。
- 测试新增订单主要作为普通取消记录保留，便于后续查看和审计。

## 2026-10-28：演示基础数据扩充到 1000 车次

**目标**

- 在 110 个国内车站不变的前提下，把演示车次从 100 扩展到 1000。
- 每条基准线路生成正反两个方向，每个方向 10 个时段。

**执行**

1. 重新运行 `scripts/gen_demo_data.py`。
2. 导入 `sql/03-demo-data-large.sql`。
3. 只重建 station、train、train_stop、train_segment、
   train_segment_price、ticket_stock 六张演示基础表。
4. 订单、支付流水和账号表没有重建。

**导入结果**

| 数据 | 数量 |
| --- | ---: |
| 车站 | 110 |
| 车次 | 1000 |
| 停站 | 4160 |
| 分段 | 3160 |
| 分段票价 | 9480 |
| 30 天分段库存 | 284400 |

车型分布为 G 600、D 120、K 160、T 80、Z 40；重复车次号为 0。
库存日期范围为 2026-10-29 ～ 2026-11-27。

**验证**

- 导入退出码为 0。
- 表计数和车次类型分布均符合生成器预期。
- 登录后查询 `北京南 → 上海虹桥` 返回 10 个车次。

## 2026-10-24：增加票价范围查询索引

**脚本**

- `sql/07-price-query-index.sql`
- 新数据库结构同步更新 `sql/01-schema.sql`

**执行**

```sql
ALTER TABLE train_segment_price
  ADD INDEX idx_price_query
    (train_id, seat_type, segment_index);
```

**原因**

票价查询条件为：

```sql
WHERE train_id = ?
  AND seat_type = ?
  AND segment_index BETWEEN ? AND ?
```

两个等值字段放在范围字段之前，才能让索引从前往后完整使用。

**验证**

```text
Index range scan using idx_price_query
over (train_id = 1 AND seat_type = 3 AND 1 <= segment_index <= 4)
cost = 2.06, rows = 4
```

MySQL 实际选择了新索引，没有全表扫描。

## 2026-10-24：重建并导入国内 100 车次演示数据

**原因**

- 旧库只有 5 个车站、1 个车次，无法支撑用户端查询分页、筛选和多线路联调。
- 用户要求重新初始化数据库，并准备恰好 100 个国内车次。

**重建前备份**

```text
sql/backups/train_ticket-before-domestic-100-20261024.sql
```

备份命令：

```powershell
mysqldump -uroot -p --single-transaction --routines --triggers `
  --databases train_ticket `
  --result-file="sql/backups/train_ticket-before-domestic-100-20261024.sql"
```

**执行**

1. `sql/01-schema.sql`：删除并重建全部 14 张表。
2. `scripts/gen_demo_data.py`：生成 `sql/03-demo-data-large.sql`。
3. 以 `utf8mb4` 导入 110 个车站、100 个车次和未来 30 天库存。

**最终数据**

| 数据 | 数量 |
| --- | ---: |
| 车站 | 110 |
| 省级地区 | 32 |
| 城市 | 87 |
| 车次 | 100 |
| G / D / K / T / Z | 60 / 12 / 16 / 8 / 4 |
| 停站 | 416 |
| 分段 | 316 |
| 分段票价 | 948 |
| 库存 | 28440 |
| 库存日期 | 2026-10-25 ～ 2026-11-23 |

**完整性验证**

- 重复车次号：0。
- 错误始发/终到站：0。
- 停站序号断裂：0。
- 分段数量和停站数量不匹配：0。
- 缺分段票价：0。
- 每个分段未来 30 天 × 3 席别库存不完整：0。
- `remaining_count <> total_count - sold_count`：0。

**回滚**

```powershell
mysql -uroot -p < sql/backups/train_ticket-before-domestic-100-20261024.sql
```

## 2026-10-24：新增 order_request 异步下单表

**环境**

- MySQL 9.7
- Schema：`train_ticket`

**脚本**

- 新建表：`sql/05-order-request.sql`
- 兼容已存在旧表的增量字段：`sql/06-order-request-reservation.sql`
- 全新安装的完整结构：`sql/01-schema.sql`

**执行**

```sql
CREATE TABLE order_request (...);

ALTER TABLE order_request
  ADD COLUMN stock_reserved TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN published_at DATETIME NULL;
```

**验证**

```sql
SHOW COLUMNS FROM order_request;
SELECT COUNT(*) FROM order_request;
```

实际结果：

- `stock_reserved`、`published_at` 均存在。
- 执行后业务表为空，当前条数为 0。
- 主键、`request_id` 唯一索引、用户索引和状态索引均已创建。

**用途**

- `request_id` 保证客户端幂等。
- `payload_json` 保存消费时使用的参数快照。
- `status` 保存 0～4 的异步状态机。
- `stock_reserved` 决定失败时是否回补 Redis。
- `published_at` 为空时由恢复任务补投 MQ。

## 环境

- 数据库：MySQL 9.7
- Schema：`train_ticket`
- 本地应用账号：`root`（仅开发环境）

## 2026-10-23：删除 `order_segment`

**原因**

- 当前订单所有乘客共享同一连续区间。
- 区间已保存在 `ticket_order.from_segment_index` 和 `to_segment_index`。
- 按票、按段展开会造成重复数据。

**执行前检查**

```sql
SELECT COUNT(*) FROM order_segment;
SELECT COUNT(*) FROM ticket_order;
SELECT COUNT(*) FROM ticket_order_item;
```

检查结果均为 0。

**执行**

```sql
DROP TABLE IF EXISTS order_segment;
```

**验证**

```sql
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = 'train_ticket'
  AND table_name = 'order_segment';
```

结果为 0。

## 2026-10-24：增加订单退票字段

**执行**

```sql
ALTER TABLE ticket_order
  ADD COLUMN refunded_count INT NOT NULL DEFAULT 0
    COMMENT '累计已退票数' AFTER paid_at,
  ADD COLUMN refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0
    COMMENT '累计退款金额' AFTER refunded_count,
  ADD COLUMN last_refunded_at DATETIME NULL
    COMMENT '最后一次退票时间' AFTER refunded_amount;

ALTER TABLE ticket_order_item
  ADD COLUMN refunded_at DATETIME NULL
    COMMENT '该张票的退票时间' AFTER status;
```

**验证**

```sql
SELECT refunded_count, refunded_amount, last_refunded_at
FROM ticket_order
LIMIT 0;

SELECT refunded_at
FROM ticket_order_item
LIMIT 0;
```

## 2026-10-24：增加超时订单扫描索引

**执行**

```sql
ALTER TABLE ticket_order
  ADD INDEX idx_order_expire (status, expire_time);
```

**用途**

```sql
SELECT id
FROM ticket_order
WHERE status = 0
  AND expire_time <= NOW()
ORDER BY expire_time, id
LIMIT 100;
```

**验证**

```sql
SHOW INDEX FROM ticket_order
WHERE Key_name = 'idx_order_expire';
```

## 2026-10-24：支付流水表

支付幂等改造新增 `pay_log` 表，完整建表语句见：

- `sql/04-pay-log.sql`

新数据库的 `sql/01-schema.sql` 也已经包含这张表。
`04-pay-log.sql` 是旧数据库的增量迁移脚本。

执行前应检查：

```sql
SHOW TABLES LIKE 'pay_log';
SELECT COUNT(*) FROM pay_log;
```

执行后验证唯一约束：

```sql
SHOW INDEX FROM pay_log;
```

**当前验证**

- 数据库 `train_ticket` 中 `pay_log` 表存在。
- 当前业务数据行数为 0。
- `pay_log` 属于交易流水，不参与演示基础数据初始化。

## 待补充规则

- 每次结构变更必须创建或追加一条记录。
- 数据修复必须记录筛选条件、预期行数、实际行数和回滚 SQL。
- 不在日志中写生产密码。
