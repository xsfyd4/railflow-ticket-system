package com.ticket.module.stock.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 查库存的入参：某个车次、某一天。
 *
 * <p>travelDate 从 query string 传，所以要加 @DateTimeFormat，
 * 否则 Spring 不知道怎么把 "2026-11-01" 转成 LocalDate。
 */
public class StockQueryDTO {

    @NotNull(message = "车次不能为空")
    private Long trainId;

    @NotNull(message = "乘车日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate travelDate;

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
}
