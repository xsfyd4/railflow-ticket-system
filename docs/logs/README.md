# 项目工作日志规范

从 2026-10-24 开始，每次实际修改项目都至少更新一个分类日志。

## 日志分类

| 文件 | 记录内容 |
| --- | --- |
| [code-changes.md](code-changes.md) | Java、配置、测试、前端和文档代码改动 |
| [database-changes.md](database-changes.md) | 建表、加字段、索引、迁移、数据修复 |
| [redis-operations.md](redis-operations.md) | Key 设计、缓存、Lua、失效、排障和压测 |
| [mq-operations.md](mq-operations.md) | Exchange、Queue、Routing Key、消费、重试和死信 |

高层阶段总结仍记录在 [`../08-项目开发日志.md`](../08-项目开发日志.md)。
分类日志记录具体操作和可复现信息。

## 强制记录规则

### 改代码

在 `code-changes.md` 记录：

- 日期和目标
- 修改文件
- 行为变化
- 验证命令和结果
- 风险、回滚方式和未完成项

### 改数据库

在 `database-changes.md` 记录：

- 环境
- 原始 SQL 或迁移文件名
- 影响行数和备份情况
- 执行前后校验 SQL
- 是否可回滚

禁止只写“改了表”，必须保留能够重放的 SQL。

### 操作 Redis

在 `redis-operations.md` 记录：

- Key 模式
- 数据类型和 TTL
- 写入、删除、批量扫描命令
- 一致性策略
- 故障和恢复操作

### 操作 MQ

在 `mq-operations.md` 记录：

- Exchange、Queue、Routing Key
- 消息格式和版本
- 生产者确认、消费者确认
- 重试、死信和补偿
- 测试消息与清理结果

## 运行日志

后端运行日志由 `backend/src/main/resources/logback-spring.xml` 分流：

| 文件 | 内容 |
| --- | --- |
| `backend/logs/application.log` | 登录、订单状态、业务结果、系统异常 |
| `backend/logs/database.log` | Mapper 执行的 SQL、参数和更新行数 |
| `backend/logs/redis.log` | Redis/Jedis、主从路由、预热和对账运行日志 |
| `backend/logs/mq.log` | RabbitMQ/AMQP 运行日志 |
| `backend/logs/archive/` | 按日期和大小滚动的历史日志 |

`logs/` 已加入 `.gitignore`，运行日志不提交 Git。
