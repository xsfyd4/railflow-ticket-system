package com.ticket.module.train.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 整体保存票价。
 *
 * <p>一次提交所有「段 × 席别」的价格，后端先全删再全插。
 * 做法简单粗暴，但管理员改价本来就是低频操作，没必要做增量比对。
 */
public class PricesSaveDTO {

    @NotEmpty(message = "票价列表不能为空")
    @Valid
    private List<TrainPriceItemDTO> prices;

    public List<TrainPriceItemDTO> getPrices() {
        return prices;
    }

    public void setPrices(List<TrainPriceItemDTO> prices) {
        this.prices = prices;
    }
}
