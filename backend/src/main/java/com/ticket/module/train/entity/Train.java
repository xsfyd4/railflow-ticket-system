package com.ticket.module.train.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应数据库 train 表。
 *
 * <p>注意 totalMileage 用 BigDecimal 而不是 double：
 * 数据库里是 DECIMAL(8,1)，用 double 会有精度误差（1234.5 可能变成 1234.4999999）。
 * **凡是钱和需要精确的小数，一律 BigDecimal。**
 *
 * <p>这张表里只有 station 的 id，没有站名。要显示"北京南"必须 JOIN，
 * 所以查询用的 VO 会直接接 JOIN 的结果（见 TrainMapper.xml）。
 */
public class Train {

    private Long id;
    private String trainNo;
    private String trainType;
    private Long startStationId;
    private Long endStationId;
    private BigDecimal totalMileage;
    private Integer status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Long getStartStationId() {
        return startStationId;
    }

    public void setStartStationId(Long startStationId) {
        this.startStationId = startStationId;
    }

    public Long getEndStationId() {
        return endStationId;
    }

    public void setEndStationId(Long endStationId) {
        this.endStationId = endStationId;
    }

    public BigDecimal getTotalMileage() {
        return totalMileage;
    }

    public void setTotalMileage(BigDecimal totalMileage) {
        this.totalMileage = totalMileage;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
