package com.ticket.module.order.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应数据库 pay_log 表：一次支付尝试的流水。
 *
 * <p>订单表说明“用户买了什么”，支付流水说明“这笔钱到底扣没扣、什么时候扣的、
 * 用哪个幂等键扣的”。两者必须分开：
 *
 * <ul>
 *   <li>一个订单可能有多条支付尝试（失败后重试、换渠道）；</li>
 *   <li>但最多只能有一条成功流水，这是钱不能重复扣的底线；</li>
 *   <li>第三方回调可能重复到达，靠 {@code externalTxnId} 唯一索引去重。</li>
 * </ul>
 */
public class PayLog {

    /** 支付处理中：流水已落库，但还没拿到支付结果。 */
    public static final int STATUS_PROCESSING = 0;

    /** 支付成功：扣款完成，订单可以置为已支付。 */
    public static final int STATUS_SUCCESS = 1;

    /** 支付失败：渠道返回失败，或订单已超时/已取消。 */
    public static final int STATUS_FAILED = 2;

    /** 已关闭：订单已由另一条支付流水完成，本次请求不扣款。 */
    public static final int STATUS_CLOSED = 3;

    private Long id;
    private String payNo;
    private String requestId;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private BigDecimal amount;
    private String channel;
    private Integer status;
    private String externalTxnId;
    private String failReason;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPayNo() {
        return payNo;
    }

    public void setPayNo(String payNo) {
        this.payNo = payNo;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getExternalTxnId() {
        return externalTxnId;
    }

    public void setExternalTxnId(String externalTxnId) {
        this.externalTxnId = externalTxnId;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
