package com.ticket.module.train.vo;

import java.math.BigDecimal;

/**
 * 返回给前端的车次信息。
 *
 * <p>比 Train 实体多了两个字段：
 * startStationName / endStationName。
 * 数据库里只有 station_id，站名是 JOIN 出来的。
 *
 * <p><b>这就是 VO 存在的意义</b>：实体装不下的东西，VO 能装。
 *
 * <p>注意：这个类**没有 from() 方法**，因为 SQL 里的列名（带下划线）
 * 加上 map-underscore-to-camel-case 之后，MyBatis 能直接映射到这些字段上。
 * Mapper 的 resultType 直接写这个类就行。
 */
public class TrainVO {

    private Long id;
    private String trainNo;
    private String trainType;

    private Long startStationId;
    private String startStationName;   // ← JOIN 出来的

    private Long endStationId;
    private String endStationName;     // ← JOIN 出来的

    private BigDecimal totalMileage;
    private Integer status;
    private String remark;

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

    public String getStartStationName() {
        return startStationName;
    }

    public void setStartStationName(String startStationName) {
        this.startStationName = startStationName;
    }

    public Long getEndStationId() {
        return endStationId;
    }

    public void setEndStationId(Long endStationId) {
        this.endStationId = endStationId;
    }

    public String getEndStationName() {
        return endStationName;
    }

    public void setEndStationName(String endStationName) {
        this.endStationName = endStationName;
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
}
