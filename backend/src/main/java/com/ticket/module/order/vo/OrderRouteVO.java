package com.ticket.module.order.vo;

/** 下单时从数据库确认出来的车次与站序。 */
public class OrderRouteVO {

    private Long trainId;
    private String trainNo;
    private Integer fromStopOrder;
    private Integer toStopOrder;

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

    public Integer getFromStopOrder() {
        return fromStopOrder;
    }

    public void setFromStopOrder(Integer fromStopOrder) {
        this.fromStopOrder = fromStopOrder;
    }

    public Integer getToStopOrder() {
        return toStopOrder;
    }

    public void setToStopOrder(Integer toStopOrder) {
        this.toStopOrder = toStopOrder;
    }
}
