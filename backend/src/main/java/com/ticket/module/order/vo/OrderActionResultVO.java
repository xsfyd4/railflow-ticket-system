package com.ticket.module.order.vo;

import java.time.LocalDateTime;

/** 支付或取消成功后返回的最新订单状态。 */
public class OrderActionResultVO {

    private String orderNo;
    private Integer status;
    private LocalDateTime paidAt;
    private LocalDateTime cancelledAt;

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
}
