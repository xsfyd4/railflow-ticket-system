package com.ticket.module.order.vo;

import java.time.LocalDateTime;

/** 支付或取消成功后返回的最新订单状态。 */
public class OrderActionResultVO {

    private String orderNo;
    private Integer status;
    private LocalDateTime paidAt;
    private LocalDateTime cancelledAt;

    /** 本次支付对应的流水号；重复支付时返回首次成功的那一条。 */
    private String payNo;

    /** 支付渠道。 */
    private String channel;

    /** true 表示本次请求命中了幂等重放，没有再次扣款。 */
    private Boolean idempotentReplay;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getPayNo() {
        return payNo;
    }

    public void setPayNo(String payNo) {
        this.payNo = payNo;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Boolean getIdempotentReplay() {
        return idempotentReplay;
    }

    public void setIdempotentReplay(Boolean idempotentReplay) {
        this.idempotentReplay = idempotentReplay;
    }
}
