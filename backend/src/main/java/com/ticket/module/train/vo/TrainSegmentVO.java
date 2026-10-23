package com.ticket.module.train.vo;

import java.math.BigDecimal;

/**
 * 返回给前端的分段信息（带起止站名）。
 */
public class TrainSegmentVO {

    private Long id;
    private Long trainId;
    private Integer segmentIndex;
    private Integer fromStopOrder;
    private Integer toStopOrder;
    private Long fromStationId;
    private String fromStationName;
    private Long toStationId;
    private String toStationName;
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

    public String getFromStationName() {
        return fromStationName;
    }

    public void setFromStationName(String fromStationName) {
        this.fromStationName = fromStationName;
    }

    public Long getToStationId() {
        return toStationId;
    }

    public void setToStationId(Long toStationId) {
        this.toStationId = toStationId;
    }

    public String getToStationName() {
        return toStationName;
    }

    public void setToStationName(String toStationName) {
        this.toStationName = toStationName;
    }

    public BigDecimal getMileage() {
        return mileage;
    }

    public void setMileage(BigDecimal mileage) {
        this.mileage = mileage;
    }
}
