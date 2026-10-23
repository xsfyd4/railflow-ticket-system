package com.ticket.module.order.vo;

import java.util.ArrayList;
import java.util.List;

/** 订单主表信息 + 每位乘车人的票。 */
public class OrderDetailVO extends OrderVO {

    private List<OrderItemVO> items = new ArrayList<>();

    public List<OrderItemVO> getItems() {
        return items;
    }

    public void setItems(List<OrderItemVO> items) {
        this.items = items;
    }
}
