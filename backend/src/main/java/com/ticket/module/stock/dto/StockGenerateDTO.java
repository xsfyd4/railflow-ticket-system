package com.ticket.module.stock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

/**
 * 生成库存的入参。
 *
 * <p>days：生成几天（默认 15 天预售期）。
 * startDate：从哪天开始（不传就从明天开始）。
 */
public class StockGenerateDTO {

    @Min(value = 1, message = "至少生成 1 天")
    @Max(value = 60, message = "一次最多生成 60 天")
    private Integer days = 15;

    private LocalDate startDate;

    public Integer getDays() {
        return days;
    }

    public void setDays(Integer days) {
        this.days = days;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }
}
