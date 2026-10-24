package com.ticket.module.ticket.vo;

import java.math.BigDecimal;

/** 一个席别的余票和票价。 */
public class TicketSeatVO {

    private Integer seatType;
    private Integer remainCount;
    private BigDecimal price;

    public TicketSeatVO() {
    }

    public TicketSeatVO(Integer seatType, Integer remainCount, BigDecimal price) {
        this.seatType = seatType;
        this.remainCount = remainCount;
        this.price = price;
    }

    public Integer getSeatType() {
        return seatType;
    }

    public void setSeatType(Integer seatType) {
        this.seatType = seatType;
    }

    public Integer getRemainCount() {
        return remainCount;
    }

    public void setRemainCount(Integer remainCount) {
        this.remainCount = remainCount;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
