package com.ticket.module.order;

import com.ticket.common.result.PageResult;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.dto.OrderQueryDTO;
import com.ticket.module.order.dto.PayOrderDTO;
import com.ticket.module.order.dto.RefundOrderDTO;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.module.order.vo.OrderActionResultVO;
import com.ticket.module.order.vo.OrderDetailVO;
import com.ticket.module.order.vo.OrderVO;
import com.ticket.module.order.vo.PayLogVO;
import com.ticket.module.order.vo.RefundResultVO;

import java.util.List;

/**
 * 订单业务接口。
 *
 * <p>下单事务、库存扣减、订单查询都要经过这里。
 */
public interface OrderService {

    /** 创建待支付订单，返回订单号和支付截止时间。 */
    CreateOrderVO create(CreateOrderDTO dto);

    /**
     * 带幂等键创建订单。
     *
     * <p>本方法本身是事务方法：幂等键通过 {@code ticket_order.request_id}
     * 唯一索引落库。调用方不要在事务内捕获 DuplicateKeyException，
     * 应在事务外层捕获并重放已有订单（见 OrderCreateService）。
     */
    CreateOrderVO createWithRequestId(CreateOrderDTO dto, String requestId);

    /**
     * 为指定用户创建订单。
     *
     * <p>只供 RabbitMQ 消费者在已持有 Redis 预扣的情况下使用。
     * 普通 HTTP 下单必须走 {@link #create(CreateOrderDTO)}，否则会少做登录用户校验。
     */
    CreateOrderVO createForUser(Long userId, CreateOrderDTO dto);

    /** 查询当前用户的订单列表。 */
    PageResult<OrderVO> page(OrderQueryDTO query);

    /** 按业务订单号查询当前用户自己的订单详情。 */
    OrderDetailVO detail(String orderNo);

    /**
     * 支付：待支付 -> 已支付。
     *
     * <p>必须幂等：同一个 {@code requestId} 重复调用只扣一次钱；
     * 订单已经支付成功时，即使换了幂等键也只返回首次成功的流水号。
     *
     * @param idempotencyKey HTTP 头 Idempotency-Key，优先级高于 DTO 里的 requestId
     * @param dto             可选的渠道与幂等键
     */
    OrderActionResultVO pay(String orderNo,
                            String idempotencyKey,
                            PayOrderDTO dto);

    /** 兼容旧调用：不传幂等键时按“用户 + 订单”生成固定键，同样只扣一次钱。 */
    default OrderActionResultVO pay(String orderNo) {
        return pay(orderNo, null, null);
    }

    /** 查询某订单的全部支付尝试，按时间倒序。 */
    List<PayLogVO> payments(String orderNo);

    /** 取消待支付订单：待支付 -> 已取消，并回补库存。 */
    OrderActionResultVO cancel(String orderNo);

    /** 对已支付订单中的指定票退票。 */
    RefundResultVO refund(String orderNo, RefundOrderDTO dto);
}
