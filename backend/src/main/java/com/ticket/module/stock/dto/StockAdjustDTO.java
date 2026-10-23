package com.ticket.module.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 手工调整某段某席别的剩余票数。
 */
public class StockAdjustDTO {

    @NotNull(message = "剩余票数不能为空")
    @Min(value = 0, message = "剩余票数不能小于 0")
    private Integer remainingCount;

    public Integer getRemainingCount() {
        return remainingCount;
    }

    public void setRemainingCount(Integer remainingCount) {
        this.remainingCount = remainingCount;
    }
}
