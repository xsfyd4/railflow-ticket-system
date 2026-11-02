USE `train_ticket`;

-- status=5 表示业务已判定失败，但 Redis 库存尚未安全释放。
-- 只有释放完成后才进入 status=4，防止库存对账把补偿中请求当成非活跃请求。
ALTER TABLE `order_request`
  ADD COLUMN `stock_released` TINYINT NOT NULL DEFAULT 0
    COMMENT '失败补偿是否已释放 Redis 库存：0=否，1=是'
    AFTER `stock_reserved`,
  ADD COLUMN `flow_state` VARCHAR(32) NOT NULL DEFAULT 'CREATED'
    COMMENT '技术流程状态'
    AFTER `stock_released`,
  ADD COLUMN `owner_token` VARCHAR(64) DEFAULT NULL
    COMMENT '当前步骤处理者令牌'
    AFTER `flow_state`,
  ADD COLUMN `lease_until` DATETIME DEFAULT NULL
    COMMENT '当前步骤租约截止时间'
    AFTER `owner_token`,
  ADD COLUMN `next_retry_at` DATETIME DEFAULT NULL
    COMMENT '下一次恢复扫描时间'
    AFTER `lease_until`,
  ADD COLUMN `last_error` VARCHAR(500) DEFAULT NULL
    COMMENT '最近一次技术错误'
    AFTER `next_retry_at`,
  ADD COLUMN `version` INT NOT NULL DEFAULT 0
    COMMENT '流程状态乐观锁版本'
    AFTER `last_error`,
  ADD KEY `idx_order_request_flow` (`flow_state`, `next_retry_at`, `id`),
  ADD KEY `idx_order_request_updated` (`updated_at`, `id`);

UPDATE `order_request`
SET `flow_state` = CASE
    WHEN `status` = 3 THEN 'SUCCEEDED'
    WHEN `status` = 4 THEN 'FAILED'
    WHEN `status` = 2 THEN 'PROCESSING'
    WHEN `status` = 1 AND `published_at` IS NOT NULL THEN 'PUBLISHED'
    WHEN `status` = 1 THEN 'RESERVED'
    WHEN `stock_reserved` = 1 THEN 'RESERVED'
    ELSE 'CREATED'
  END,
  `stock_released` = CASE
    WHEN `status` = 4 THEN 1
    ELSE 0
  END;

CREATE TABLE IF NOT EXISTS `order_flow_event` (
  `event_id`       BIGINT       NOT NULL AUTO_INCREMENT,
  `request_id`     VARCHAR(64)  NOT NULL,
  `from_state`     VARCHAR(32)           DEFAULT NULL,
  `to_state`       VARCHAR(32)  NOT NULL,
  `owner_token`    VARCHAR(64)           DEFAULT NULL,
  `detail`         VARCHAR(500)          DEFAULT NULL,
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_flow_event_request` (`request_id`, `event_id`),
  KEY `idx_flow_event_created` (`created_at`, `event_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '异步下单流程状态变化流水';

CREATE TABLE IF NOT EXISTS `maintenance_cursor` (
  `job_name`       VARCHAR(64) NOT NULL,
  `last_event_id`  BIGINT      NOT NULL DEFAULT 0,
  `full_last_id`   BIGINT      NOT NULL DEFAULT 0,
  `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`job_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '后台扫描任务持久化游标';

INSERT INTO `order_flow_event`
    (`request_id`, `from_state`, `to_state`, `detail`, `created_at`)
SELECT `request_id`, NULL, `flow_state`, 'migration-backfill', `updated_at`
FROM `order_request`
WHERE NOT EXISTS (
    SELECT 1
    FROM `order_flow_event` e
    WHERE e.`request_id` = `order_request`.`request_id`
);

INSERT INTO `maintenance_cursor` (`job_name`)
VALUES ('ORDER_FLOW')
ON DUPLICATE KEY UPDATE `job_name` = VALUES(`job_name`);

UPDATE `maintenance_cursor`
SET `last_event_id` = COALESCE((
    SELECT MAX(`event_id`) FROM `order_flow_event`
), 0)
WHERE `job_name` = 'ORDER_FLOW';
