-- 增量迁移：优化“某车次、某席别、连续段区间”的票价查询。
USE `train_ticket`;

ALTER TABLE `train_segment_price`
  ADD INDEX `idx_price_query`
    (`train_id`, `seat_type`, `segment_index`);
