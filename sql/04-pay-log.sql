-- ============================================================================
--  增量迁移：新增支付流水表 pay_log
--  适用：已经按 01-schema.sql 建过库、不想重建数据的现有环境
--  执行：mysql -u root -p < sql/04-pay-log.sql
-- ============================================================================

USE `train_ticket`;

CREATE TABLE IF NOT EXISTS `pay_log` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pay_no`           VARCHAR(40)   NOT NULL                COMMENT '本系统支付流水号（唯一）',
  `request_id`       VARCHAR(64)   NOT NULL                COMMENT '幂等键：同一次支付请求重试必须相同',
  `order_id`         BIGINT        NOT NULL                COMMENT '订单 ticket_order.id',
  `order_no`         VARCHAR(32)   NOT NULL                COMMENT '订单号（冗余，方便排障）',
  `user_id`          BIGINT        NOT NULL                COMMENT '付款账号 sys_user.id',
  `amount`           DECIMAL(10,2) NOT NULL                COMMENT '本次支付金额（元）',
  `channel`          VARCHAR(20)   NOT NULL DEFAULT 'MOCK' COMMENT '支付渠道：MOCK/ALIPAY/WECHAT',
  `status`           TINYINT       NOT NULL DEFAULT 0      COMMENT '0=处理中，1=成功，2=失败，3=已关闭',
  `external_txn_id`  VARCHAR(64)            DEFAULT NULL   COMMENT '第三方支付流水号（回调幂等）',
  `fail_reason`      VARCHAR(255)           DEFAULT NULL   COMMENT '失败或关闭原因',
  `paid_at`          DATETIME               DEFAULT NULL   COMMENT '支付成功时间',
  `success_order_id` BIGINT      GENERATED ALWAYS AS
                                 (IF(`status` = 1, `order_id`, NULL)) STORED
                                                            COMMENT '仅成功流水有值，用于一单一条成功流水',
  `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                           ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_no` (`pay_no`),
  UNIQUE KEY `uk_pay_request` (`request_id`),
  UNIQUE KEY `uk_pay_external` (`external_txn_id`),
  UNIQUE KEY `uk_pay_success_order` (`success_order_id`),
  KEY `idx_pay_order` (`order_id`, `created_at`),
  KEY `idx_pay_user` (`user_id`, `created_at`),
  KEY `idx_pay_status` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付流水表（支付幂等与对账）';
