package com.ticket.module.order.dto;

/**
 * 支付请求。
 *
 * <p>幂等键有两种传法，优先级从高到低：
 * <ol>
 *   <li>HTTP 头 {@code Idempotency-Key}；</li>
 *   <li>请求体 {@code requestId}；</li>
 *   <li>都没传时，服务端按“用户 + 订单”生成固定键，
 *       保证同一个用户对同一张订单重复点击支付也只会扣一次钱。</li>
 * </ol>
 */
public class PayOrderDTO {

    /** 幂等键，最长 64 位。 */
    private String requestId;

    /** 支付渠道。当前只实现 MOCK 模拟渠道。 */
    private String channel;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
