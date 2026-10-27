-- 增量迁移：异步下单请求的 Redis 预扣标记和 MQ 投递时间
USE `train_ticket`;

ALTER TABLE `order_request`
  ADD COLUMN `stock_reserved` TINYINT NOT NULL DEFAULT 0
    COMMENT '是否已在 Redis 预扣库存：0=否，1=是'
    AFTER `status`,
  ADD COLUMN `published_at` DATETIME NULL
    COMMENT '成功投递到 RabbitMQ 的时间'
    AFTER `stock_reserved`;
