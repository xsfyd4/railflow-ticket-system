package com.ticket.module.order.dto;

/**
 * RabbitMQ 下单消息。
 *
 * <p>只传 requestId，不把完整订单 JSON 再复制一份到消息里。
 * 消费者以 order_request 中已经持久化的 payload 为准，避免消息被篡改或版本不一致。
 */
public record OrderCreateMessage(int version, String requestId) {

    public static final int CURRENT_VERSION = 1;

    public static OrderCreateMessage of(String requestId) {
        return new OrderCreateMessage(CURRENT_VERSION, requestId);
    }
}
