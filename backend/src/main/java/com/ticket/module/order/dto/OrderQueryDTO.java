package com.ticket.module.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * “我的订单”列表查询参数。
 *
 * <p>status 不传表示查询全部状态；page 和 size 即使不传，
 * Service 也会补上安全默认值。
 */
public class OrderQueryDTO {

    @Min(value = 0, message = "订单状态不能小于 0")
    @Max(value = 5, message = "订单状态不能大于 5")
    private Integer status;

    private Integer page = 1;
    private Integer size = 10;

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
