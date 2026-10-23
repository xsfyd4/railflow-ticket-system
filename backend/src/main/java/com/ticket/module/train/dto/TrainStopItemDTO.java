package com.ticket.module.train.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 停站列表里的「一站」。
 *
 * <p>注意没有 stopOrder：站序由后端按这个对象在数组里的位置自动生成。
 * 前端只要保证数组顺序正确（拖动排序后的结果），后端就按 1、2、3... 编。
 *
 * <p>时间用 @JsonFormat 指定格式，这样前端传 "08:00" 就能正确解析成 LocalTime。
 */
public class TrainStopItemDTO {

    @NotNull(message = "站点不能为空")
    private Long stationId;

    /** 始发站没有到达时间，可以为 null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime arrivalTime;

    /** 终到站没有发车时间，可以为 null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime departureTime;

    /** 相对发车日的天数：0=当天，1=次日（跨天车次） */
    private Integer dayOffset;

    /** 从始发站算起的累计里程，不填默认 0 */
    private BigDecimal mileage;

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
}
