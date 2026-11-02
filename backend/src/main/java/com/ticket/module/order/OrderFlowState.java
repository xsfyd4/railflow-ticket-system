package com.ticket.module.order;

/**
 * 异步下单的技术流程状态。
 *
 * <p>它与面向用户的 {@code order_request.status} 分开：status 表示业务结果，
 * flowState 用于恢复任务判断请求卡在 Redis、MQ 还是消费者阶段。
 */
public enum OrderFlowState {
    CREATED,
    RESERVING,
    RESERVED,
    PUBLISHING,
    PUBLISHED,
    PROCESSING,
    RELEASING,
    SUCCEEDED,
    FAILED,
    MANUAL_REVIEW;

    public boolean terminal() {
        return this == SUCCEEDED
                || this == FAILED
                || this == MANUAL_REVIEW;
    }
}
