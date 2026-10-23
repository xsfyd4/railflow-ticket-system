package com.ticket.module.order.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对应数据库 ticket_order 表。
 */
public class TicketOrder {

    private Long id;
    private String orderNo;
    private Long userId;
    private Long trainId;
    private String trainNo;
    private LocalDate travelDate;
    private Long fromStationId;
    private Long toStationId;
    private Integer fromSegmentIndex;
    private Integer toSegmentIndex;
    private Integer seatType;
    private Integer passengerCount;
    private BigDecimal totalAmount;
    private Integer status;
    private LocalDateTime expireTime;
    private LocalDateTime paidAt;
    private Integer refundedCount;
    private BigDecimal refundedAmount;
    private LocalDateTime lastRefundedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getTrainId() {
        return trainId;
    }

    public void setTrainId(Long trainId) {
        this.trainId = trainId;
    }

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
    }

    public LocalDate getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(LocalDate travelDate) {
        this.travelDate = travelDate;
    }

    public Long getFromStationId() {
        return fromStationId;
    }

    public void setFromStationId(Long fromStationId) {
        this.fromStationId = fromStationId;
    }

    public Long getToStationId() {
        return toStationId;
    }

    public void setToStationId(Long toStationId) {
        this.toStationId = toStationId;
    }

    public Integer getFromSegmentIndex() {
        return fromSegmentIndex;
    }

    public void setFromSegmentIndex(Integer fromSegmentIndex) {
        this.fromSegmentIndex = fromSegmentIndex;
    }

    public Integer getToSegmentIndex() {
        return toSegmentIndex;
    }

    public void setToSegmentIndex(Integer toSegmentIndex) {
        this.toSegmentIndex = toSegmentIndex;
    }

    public Integer getSeatType() {
        return seatType;
    }

    public void setSeatType(Integer seatType) {
        this.seatType = seatType;
    }

    public Integer getPassengerCount() {
        return passengerCount;
    }

    public void setPassengerCount(Integer passengerCount) {
        this.passengerCount = passengerCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
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

    public LocalDateTime getLastRefundedAt() {
        return lastRefundedAt;
    }

    public void setLastRefundedAt(LocalDateTime lastRefundedAt) {
        this.lastRefundedAt = lastRefundedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
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
