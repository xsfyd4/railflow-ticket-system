package com.ticket.module.order.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退票成功后的订单汇总。 */
public class RefundResultVO {

    private String orderNo;
    private Integer orderStatus;
    private Integer refundedCount;
    private BigDecimal refundedAmount;
    private Integer remainingTicketCount;
    private LocalDateTime refundedAt;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Integer getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(Integer orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Integer getRefundedCount() {
        return refundedCount;
    }

    public void setRefundedCount(Integer refundedCount) {
        this.refundedCount = refundedCount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public Integer getRemainingTicketCount() {
        return remainingTicketCount;
    }

    public void setRemainingTicketCount(Integer remainingTicketCount) {
        this.remainingTicketCount = remainingTicketCount;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public void setRefundedAt(LocalDateTime refundedAt) {
        this.refundedAt = refundedAt;
    }
}
