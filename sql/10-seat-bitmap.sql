-- 已有数据库升级脚本：增加具体座位位图和订单座位位号。
-- 全新安装直接执行 01-schema.sql，不需要再执行本文件。

ALTER TABLE `ticket_stock`
    ADD COLUMN `sold_seat_mask` VARBINARY(160) NULL
    COMMENT '已售具体座位位图，座位号从1开始按位存储'
    AFTER `remaining_count`;

ALTER TABLE `ticket_order_item`
    ADD COLUMN `seat_index` INT NULL
    COMMENT '席别内连续座位位号，从1开始'
    AFTER `seat_no`;
