package com.ticket.module.train.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 新增 / 修改车次的入参。
 *
 * <p>这里只填「车次是什么」，不填停站。
 * 停站是另一个接口（PUT /trains/{id}/stops）负责的，
 * 因为一趟车可能有十几站，拖动排序、增删都要整体提交。
 */
public class TrainSaveDTO {

    /** 车次号：一个字母 + 1~4 位数字，比如 G1001、D312、K23 */
    @NotBlank(message = "车次号不能为空")
    @Pattern(regexp = "^[GDKZT]\\d{1,4}$", message = "车次号格式不对，应形如 G1001")
    private String trainNo;

    /** 类型：只能是一个字母 */
    @NotBlank(message = "车次类型不能为空")
    @Pattern(regexp = "^[GDKZT]$", message = "车次类型只能是 G/D/K/T/Z")
    private String trainType;

    @NotNull(message = "始发站不能为空")
    private Long startStationId;

    @NotNull(message = "终到站不能为空")
    private Long endStationId;

    /** 全程里程，可以不填（等配完停站后由程序算） */
    private BigDecimal totalMileage;

    /** 1=启用，0=停运；不传时由 Service 默认 1 */
    private Integer status;

    @Size(max = 255, message = "备注最长 255 个字")
    private String remark;

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
}
