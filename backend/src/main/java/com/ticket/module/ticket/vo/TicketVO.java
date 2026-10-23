package com.ticket.module.ticket.vo;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 查票结果：一个车次一条，里面带所有席别。
 * 前端切换席别时只在本地过滤，不用再请求一次接口。
 */
public class TicketVO {

    private Long trainId;
    private String trainNo;
    private String trainType;
    private String fromStationName;
    private String toStationName;
    private LocalTime departTime;
    private LocalTime arriveTime;
    private Integer durationMinutes;
    private BigDecimal mileage;
    private List<TicketSeatVO> seats = new ArrayList<>();

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

    public String getTrainType() {
        return trainType;
    }

    public void setTrainType(String trainType) {
        this.trainType = trainType;
    }

    public String getFromStationName() {
        return fromStationName;
    }

    public void setFromStationName(String fromStationName) {
        this.fromStationName = fromStationName;
    }

    public String getToStationName() {
        return toStationName;
    }

    public void setToStationName(String toStationName) {
        this.toStationName = toStationName;
    }

    public LocalTime getDepartTime() {
        return departTime;
    }

    public void setDepartTime(LocalTime departTime) {
        this.departTime = departTime;
    }

    public LocalTime getArriveTime() {
        return arriveTime;
    }

    public void setArriveTime(LocalTime arriveTime) {
        this.arriveTime = arriveTime;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public BigDecimal getMileage() {
        return mileage;
    }

    public void setMileage(BigDecimal mileage) {
        this.mileage = mileage;
    }

    public List<TicketSeatVO> getSeats() {
        return seats;
    }

    public void setSeats(List<TicketSeatVO> seats) {
        this.seats = seats;
    }
}
