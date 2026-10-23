package com.ticket.module.train.entity;

import java.math.BigDecimal;

/**
 * 对应 train_segment 表（相邻两站切出来的段）。
 *
 * <p>第 k 段 = 第 k 站 → 第 k+1 站。
 * 停站有 N 站时，分段就有 N-1 条。
 */
public class TrainSegment {

    private Long id;
    private Long trainId;
    private Integer segmentIndex;
    private Integer fromStopOrder;
    private Integer toStopOrder;
    private Long fromStationId;
    private Long toStationId;
    private BigDecimal mileage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTrainId() {
        return trainId;
    }

    public void setTrainId(Long trainId) {
        this.trainId = trainId;
    }

    public Integer getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(Integer segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public Integer getFromStopOrder() {
        return fromStopOrder;
    }

    public void setFromStopOrder(Integer fromStopOrder) {
        this.fromStopOrder = fromStopOrder;
    }

    public Integer getToStopOrder() {
        return toStopOrder;
    }

    public void setToStopOrder(Integer toStopOrder) {
        this.toStopOrder = toStopOrder;
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

    public BigDecimal getMileage() {
        return mileage;
    }

    public void setMileage(BigDecimal mileage) {
        this.mileage = mileage;
    }
}
