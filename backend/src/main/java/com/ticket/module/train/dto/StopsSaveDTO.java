package com.ticket.module.train.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 「整体保存停站」的入参。
 *
 * <p>为什么是整体提交，而不是一站点一次接口？
 * 因为管理员会拖动排序、删中间站、插新站——这些操作都会让**站序整体变化**。
 * 一次提交整个列表，后端才能一次性重排 stop_order、重建分段。
 *
 * <p>数组顺序 = 列车行驶顺序。后端按 index + 1 生成 stop_order。
 */
public class StopsSaveDTO {

    @NotEmpty(message = "停站列表不能为空")
    @Size(min = 2, message = "至少要有始发站和终到站两站")
    @Valid
    private List<TrainStopItemDTO> stops;

    public List<TrainStopItemDTO> getStops() {
        return stops;
    }

    public void setStops(List<TrainStopItemDTO> stops) {
        this.stops = stops;
    }
}
