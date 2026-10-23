package com.ticket.module.order.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 提交订单的入参。
 */
public class CreateOrderDTO {

    @NotNull(message = "车次不能为空")
    private Long trainId;

    @NotNull(message = "乘车日期不能为空")
    @FutureOrPresent(message = "乘车日期不能早于今天")
    private LocalDate travelDate;

    @NotNull(message = "出发站不能为空")
    private Long fromStationId;

    @NotNull(message = "到达站不能为空")
    private Long toStationId;

    /** 1=商务座，2=一等座，3=二等座 */
    @NotNull(message = "席别不能为空")
    @Min(value = 1, message = "席别只能是 1、2、3")
    @Max(value = 3, message = "席别只能是 1、2、3")
    private Integer seatType;

    @NotEmpty(message = "至少选择一位乘车人")
    @Size(max = 5, message = "一次最多为 5 位乘车人购票")
    private List<@NotNull(message = "乘车人 id 不能为空") Long> passengerIds;

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

    public Integer getSeatType() {
        return seatType;
    }

    public void setSeatType(Integer seatType) {
        this.seatType = seatType;
    }

    public List<Long> getPassengerIds() {
        return passengerIds;
    }

    public void setPassengerIds(List<Long> passengerIds) {
        this.passengerIds = passengerIds;
    }
}
