package com.ticket.module.train.entity;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 对应 train_stop 表（车次停站）。
 *
 * <p>stop_order 是"第几站"，从 1 开始。**它由后端按提交顺序生成，
 * 不由前端传**——前端拖动排序后，数组顺序就是新的站序。
 */
public class TrainStop {

    private Long id;
    private Long trainId;
    private Integer stopOrder;
    private Long stationId;
    private LocalTime arrivalTime;
    private LocalTime departureTime;
    private Integer dayOffset;
    private BigDecimal mileage;
    private Integer isStart;
    private Integer isEnd;

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

    public Integer getStopOrder() {
        return stopOrder;
    }

    public void setStopOrder(Integer stopOrder) {
        this.stopOrder = stopOrder;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public LocalTime getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(LocalTime arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public LocalTime getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(LocalTime departureTime) {
        this.departureTime = departureTime;
    }

    public Integer getDayOffset() {
        return dayOffset;
    }

    public void setDayOffset(Integer dayOffset) {
        this.dayOffset = dayOffset;
    }

    public BigDecimal getMileage() {
        return mileage;
    }

    public void setMileage(BigDecimal mileage) {
        this.mileage = mileage;
    }

    public Integer getIsStart() {
        return isStart;
    }

    public void setIsStart(Integer isStart) {
        this.isStart = isStart;
    }

    public Integer getIsEnd() {
        return isEnd;
    }

    public void setIsEnd(Integer isEnd) {
        this.isEnd = isEnd;
    }
}
