package com.ticket.module.train.vo;

import java.math.BigDecimal;

/**
 * 返回给前端的票价：某段、某席别、多少钱。
 */
public class TrainPriceVO {

    private Integer segmentIndex;
    private Integer seatType;
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
