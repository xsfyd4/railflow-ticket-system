-- 增量迁移：异步下单请求表
USE `train_ticket`;

CREATE TABLE IF NOT EXISTS `order_request` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `request_id`         VARCHAR(64)  NOT NULL,
  `user_id`            BIGINT       NOT NULL,
  `payload_json`       TEXT         NOT NULL,
  `train_id`           BIGINT       NOT NULL,
  `travel_date`        DATE         NOT NULL,
  `seat_type`          TINYINT      NOT NULL,
  `from_segment_index` INT          NOT NULL,
  `to_segment_index`   INT          NOT NULL,
  `passenger_count`    INT          NOT NULL,
  `status`             TINYINT      NOT NULL DEFAULT 0,
  `order_id`           BIGINT                DEFAULT NULL,
  `order_no`           VARCHAR(32)           DEFAULT NULL,
  `fail_reason`        VARCHAR(255)          DEFAULT NULL,
  `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_request` (`request_id`),
  KEY `idx_order_request_user` (`user_id`, `created_at`),
  KEY `idx_order_request_status` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '异步下单请求表';
