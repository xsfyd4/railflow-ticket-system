USE `train_ticket`;

/*
 * 将瞬时技术状态迁移为关键持久化状态：
 * RESERVING -> CREATED（保留 owner_token 和租约）
 * PUBLISHING -> RESERVED / PUBLISHED
 * PROCESSING -> RESERVED / PUBLISHED / SUCCEEDED / RELEASING
 */
START TRANSACTION;

UPDATE `order_request`
SET `flow_state` = CASE
        WHEN `flow_state` = 'RESERVING' THEN 'CREATED'
        WHEN `flow_state` = 'PUBLISHING' THEN
            CASE
                WHEN `published_at` IS NOT NULL THEN 'PUBLISHED'
                ELSE 'RESERVED'
            END
        WHEN `flow_state` = 'PROCESSING' THEN
            CASE
                WHEN `status` = 3 THEN 'SUCCEEDED'
                WHEN `status` = 4 AND `stock_released` = 1 THEN 'FAILED'
                WHEN `status` = 4 THEN 'RELEASING'
                WHEN `published_at` IS NOT NULL THEN 'PUBLISHED'
                ELSE 'RESERVED'
            END
        ELSE `flow_state`
    END,
    `status` = CASE
        WHEN `flow_state` = 'PROCESSING' AND `status` = 2 THEN 1
        ELSE `status`
    END,
    `owner_token` = CASE
        WHEN `flow_state` IN ('PUBLISHING', 'PROCESSING') THEN NULL
        ELSE `owner_token`
    END,
    `lease_until` = CASE
        WHEN `flow_state` IN ('PUBLISHING', 'PROCESSING') THEN NULL
        ELSE `lease_until`
    END
WHERE `flow_state` IN ('RESERVING', 'PUBLISHING', 'PROCESSING');

COMMIT;
