# MQ 操作日志

## 2026-10-28：RabbitMQ 异步下单正式链路压测

**方法**

- `scripts/async_order_test.py`
- 32 并发提交 200 笔唯一异步订单。
- 每笔轮询 `order_request`，等待消费者落库为状态 3，再调用取消接口恢复库存。

**结果**

```text
成功 200 / 失败 0
耗时 1.481 秒
吞吐 135.04 单/秒
提交 P99 61.93ms
端到端 P99 337.29ms
ticket.order.create: ready=0, unacknowledged=0
ticket.order.dead: ready=0
```

**验证**

- RabbitMQ 消费完成，无积压、无死信。
- `order_request` 累计 210 条成功记录，失败 0。
- 异步订单取消后库存恢复。

## 2026-10-24：RabbitMQ 异步下单主链路

**拓扑**

| 对象 | 名称 |
| --- | --- |
| Exchange | `ticket.order.exchange` |
| Queue | `ticket.order.create` |
| Routing Key | `ticket.order.create` |
| 死信 Exchange | `ticket.order.dlx` |
| 死信 Queue | `ticket.order.dead` |
| 死信 Routing Key | `ticket.order.dead` |

**消息**

```json
{"version":1,"requestId":"客户端幂等键"}
```

消息只传 requestId；完整下单参数以 `order_request.payload_json` 为准。

**消费者**

- 消费队列：`ticket.order.create`
- 并发：2～8
- prefetch：20
- 每个应用实例最多手动重试 3 次
- 无法解析的消息拒绝并进入死信队列
- 业务异常最终标记 `order_request.status=4` 并回补 Redis

**幂等**

- `request_id` 唯一索引。
- 消费者对 `order_request` 行执行 `FOR UPDATE`。
- 状态 3/4 为终态，重复消息直接返回。
- 已用真实 RabbitMQ 重复投递验证：订单仍只有一笔。

**恢复**

- 状态 1 且 `published_at IS NULL`：补偿任务重新投递。
- 状态 0/2 超过 10 分钟：补偿任务标记失败并修复库存。

**运行检查**

```powershell
docker exec rabbitmq-ticket rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

当前检查结果：

```text
ticket.order.dead    0 0 0
ticket.order.create  0 0 0
```

说明应用已自动创建两个 durable 队列，测试结束后没有积压和未确认消息。

## 当前状态

截至 2026-10-24：

- 已添加 RabbitMQ/Spring AMQP 依赖。
- 已配置 `localhost:5672`。
- 健康检查曾声明 `ticket.health` 队列并测试发送、接收。
- 下单、支付、取消、退票和超时任务均未使用 MQ。

## 当前运行日志

RabbitMQ/AMQP 日志写入：

```text
backend/logs/mq.log
```

## 后续写入规则

每次 MQ 改造必须记录：

- Exchange 名称和类型
- Queue 名称
- Routing Key
- 消息 JSON 示例和版本
- 生产者确认
- 消费者确认和幂等键
- 重试次数
- 死信 Exchange/Queue
- 测试消息和清理结果

## 尚未执行

- 异步下单队列。
- 订单延迟取消消息。
- 支付/退票事件。
- 死信队列。
- 消息补偿任务。
