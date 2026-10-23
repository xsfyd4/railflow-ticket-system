package com.ticket.module.train.entity;

import java.math.BigDecimal;

/**
 * 对应 train_segment_price 表（每段每席别的票价）。
 *
 * <p>seatType：1=商务座，2=一等座，3=二等座。
 */
public class TrainSegmentPrice {

    private Long id;
    private Long trainId;
    private Integer segmentIndex;
    private Integer seatType;
    private BigDecimal price;

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
