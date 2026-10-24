package com.ticket.module.order.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水展示对象。前端和排障都用它看“这笔订单到底付过几次”。
 */
public class PayLogVO {

    private String payNo;
    private String orderNo;
    private BigDecimal amount;
    private String channel;
    private Integer status;
    private String externalTxnId;
    private String failReason;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;

    public String getPayNo() {
        return payNo;
    }

    public void setPayNo(String payNo) {
        this.payNo = payNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
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
}
