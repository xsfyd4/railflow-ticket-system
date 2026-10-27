package com.ticket.module.order.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 异步下单请求的持久化状态。
 *
 * <p>Redis 和 RabbitMQ 都不是可靠存储：应用重启后内存会丢、消息可能重复投递。
 * 这张表负责回答“用户提交过什么、库存是否预扣过、最终订单是哪一笔”。
 */
public class OrderRequest {

    private Long id;
    private String requestId;
    private Long userId;
    private String payloadJson;
    private Long trainId;
    private LocalDate travelDate;
    private Integer seatType;
    private Integer fromSegmentIndex;
    private Integer toSegmentIndex;
    private Integer passengerCount;
    private Integer status;
    private Integer stockReserved;
    private LocalDateTime publishedAt;
    private Long orderId;
    private String orderNo;
    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Long getTrainId() {
        return trainId;
    }

    public void setTrainId(Long trainId) {
        this.trainId = trainId;
    }

    public LocalDate getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(LocalDate travelDate) {
        this.travelDate = travelDate;
    }

    public Integer getSeatType() {
        return seatType;
    }

    public void setSeatType(Integer seatType) {
        this.seatType = seatType;
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

    public Integer getPassengerCount() {
        return passengerCount;
    }

    public void setPassengerCount(Integer passengerCount) {
        this.passengerCount = passengerCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStockReserved() {
        return stockReserved;
    }

    public void setStockReserved(Integer stockReserved) {
        this.stockReserved = stockReserved;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
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

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
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
