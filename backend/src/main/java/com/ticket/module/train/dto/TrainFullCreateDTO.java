package com.ticket.module.train.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 一次创建“可售车次”的完整入参。
 *
 * <p>始发站、终到站和总里程由停站列表首尾自动推导，前端不能伪造。
 */
public class TrainFullCreateDTO {

    @NotBlank(message = "车次号不能为空")
    @Pattern(regexp = "^[GDKZT]\\d{1,4}$",
            message = "车次号格式不对，应形如 G1001")
    private String trainNo;

    @NotBlank(message = "车次类型不能为空")
    @Pattern(regexp = "^[GDKZT]$", message = "车次类型只能是 G/D/K/T/Z")
    private String trainType;

    private Integer status;

    @Size(max = 255, message = "备注最长 255 个字")
    private String remark;

    @NotEmpty(message = "停站列表不能为空")
    @Size(min = 2, message = "至少要有始发站和终到站两站")
    @Valid
    private List<TrainStopItemDTO> stops;

    @NotEmpty(message = "票价列表不能为空")
    @Valid
    private List<TrainPriceItemDTO> prices;

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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public List<TrainStopItemDTO> getStops() {
        return stops;
    }

    public void setStops(List<TrainStopItemDTO> stops) {
        this.stops = stops;
    }

    public List<TrainPriceItemDTO> getPrices() {
        return prices;
    }

    public void setPrices(List<TrainPriceItemDTO> prices) {
        this.prices = prices;
    }
}
