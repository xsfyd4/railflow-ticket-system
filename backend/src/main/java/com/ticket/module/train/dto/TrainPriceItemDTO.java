package com.ticket.module.train.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 一条票价：某一段、某个席别、多少钱。
 */
public class TrainPriceItemDTO {

    /** 第几段，从 1 开始 */
    @NotNull(message = "段号不能为空")
    @Min(value = 1, message = "段号从 1 开始")
    private Integer segmentIndex;

    /** 1=商务座，2=一等座，3=二等座 */
    @NotNull(message = "席别不能为空")
    @Min(value = 1, message = "席别只能是 1/2/3")
    @Max(value = 3, message = "席别只能是 1/2/3")
    private Integer seatType;

    @NotNull(message = "票价不能为空")
    @DecimalMin(value = "0.01", message = "票价必须大于 0")
    private BigDecimal price;

    public Integer getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(Integer segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public Integer getSeatType() {
        return seatType;
    }

    public void setSeatType(Integer seatType) {
        this.seatType = seatType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
