package com.ticket.module.order;

import com.ticket.common.result.PageResult;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.dto.OrderQueryDTO;
import com.ticket.module.order.dto.RefundOrderDTO;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.module.order.vo.OrderActionResultVO;
import com.ticket.module.order.vo.OrderDetailVO;
import com.ticket.module.order.vo.OrderVO;
import com.ticket.module.order.vo.RefundResultVO;

/**
 * 订单业务接口。
 *
 * <p>下单事务、库存扣减、订单查询都要经过这里。
 */
public interface OrderService {

    /** 创建待支付订单，返回订单号和支付截止时间。 */
    CreateOrderVO create(CreateOrderDTO dto);

    /** 查询当前用户的订单列表。 */
    PageResult<OrderVO> page(OrderQueryDTO query);

    /** 按业务订单号查询当前用户自己的订单详情。 */
    OrderDetailVO detail(String orderNo);

    /** 模拟支付：待支付 -> 已支付。 */
    OrderActionResultVO pay(String orderNo);

    /** 取消待支付订单：待支付 -> 已取消，并回补库存。 */
    OrderActionResultVO cancel(String orderNo);

    /** 对已支付订单中的指定票退票。 */
    RefundResultVO refund(String orderNo, RefundOrderDTO dto);
}
