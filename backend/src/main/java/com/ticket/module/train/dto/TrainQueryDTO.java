package com.ticket.module.train.dto;

/**
 * 车次列表的查询条件。
 *
 * <p>trainNo 用模糊匹配（输 "G10" 能搜到 G1001、G1012），
 * trainType 和 status 用精确匹配。
 */
public class TrainQueryDTO {

    /** 车次号，模糊匹配 */
    private String trainNo;

    /** 车次类型：G / D / K / T / Z */
    private String trainType;

    /** 1=启用，0=停运 */
    private Integer status;

    private Integer page = 1;
    private Integer size = 10;

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
    }

    public String getTrainType() {
        return trainType;
    }

    public void setTrainType(String trainType) {
        this.trainType = trainType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
