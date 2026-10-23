package com.ticket.module.order.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 选择订单明细进行退票；一次可以退一张或多张票。 */
public class RefundOrderDTO {

    @NotEmpty(message = "至少选择一张要退的票")
    @Size(max = 5, message = "一次最多退 5 张票")
    private List<@NotNull(message = "订单明细 id 不能为空") Long> orderItemIds;

    public List<Long> getOrderItemIds() {
        return orderItemIds;
    }

    public void setOrderItemIds(List<Long> orderItemIds) {
        this.orderItemIds = orderItemIds;
    }
}
