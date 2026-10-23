package com.ticket.module.ticket.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 查票入参：出发站 + 到达站 + 日期（+ 可选席别）。
 * seatType 不传就返回所有席别。
 */
public class TicketSearchDTO {

    @NotNull(message = "出发站不能为空")
    private Long fromStationId;

    @NotNull(message = "到达站不能为空")
    private Long toStationId;

    @NotNull(message = "乘车日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate travelDate;

    /** 1=商务座，2=一等座，3=二等座；不传表示全部 */
    private Integer seatType;

    private Integer page = 1;
    private Integer size = 10;

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

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}
