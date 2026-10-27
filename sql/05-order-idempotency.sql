-- ============================================================================
--  增量迁移：给同步下单增加幂等键
--  执行：mysql -u root -p < sql/05-order-idempotency.sql
--  说明：可重复执行；已有异步下单的 order_request 不受影响。
-- ============================================================================

USE `train_ticket`;

-- 1) 加列（如果不存在）
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'train_ticket'
    AND TABLE_NAME = 'ticket_order'
    AND COLUMN_NAME = 'request_id'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `ticket_order` ADD COLUMN `request_id` VARCHAR(64) DEFAULT NULL COMMENT ''同步下单幂等键（唯一，异步下单为空）'' AFTER `order_no`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 加唯一索引（如果不存在）
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'train_ticket'
    AND TABLE_NAME = 'ticket_order'
    AND INDEX_NAME = 'uk_order_request'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `ticket_order` ADD UNIQUE KEY `uk_order_request` (`request_id`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
