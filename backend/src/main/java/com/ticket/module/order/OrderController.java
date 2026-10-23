package com.ticket.module.order;

import com.ticket.common.result.PageResult;
import com.ticket.common.result.R;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.dto.OrderQueryDTO;
import com.ticket.module.order.dto.RefundOrderDTO;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.module.order.vo.OrderActionResultVO;
import com.ticket.module.order.vo.OrderDetailVO;
import com.ticket.module.order.vo.OrderVO;
import com.ticket.module.order.vo.RefundResultVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端订单接口入口。
 */
@RestController
@RequestMapping("/api/user/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 只创建待支付订单，库存已经在本事务里被预占。
     */
    @PostMapping
    public R<CreateOrderVO> create(@RequestBody @Valid CreateOrderDTO dto) {
        return R.ok("下单成功", orderService.create(dto));
    }

    /** GET /api/user/orders?status=0&page=1&size=10 */
    @GetMapping
    public R<PageResult<OrderVO>> page(@Valid OrderQueryDTO query) {
        return R.ok(orderService.page(query));
    }

    /** GET /api/user/orders/{orderNo} */
    @GetMapping("/{orderNo}")
    public R<OrderDetailVO> detail(@PathVariable String orderNo) {
        return R.ok(orderService.detail(orderNo));
    }

    /** POST /api/user/orders/{orderNo}/pay */
    @PostMapping("/{orderNo}/pay")
    public R<OrderActionResultVO> pay(@PathVariable String orderNo) {
        return R.ok("支付成功", orderService.pay(orderNo));
    }

    /** POST /api/user/orders/{orderNo}/cancel */
    @PostMapping("/{orderNo}/cancel")
    public R<OrderActionResultVO> cancel(@PathVariable String orderNo) {
        return R.ok("取消成功", orderService.cancel(orderNo));
    }

    /** POST /api/user/orders/{orderNo}/refund */
    @PostMapping("/{orderNo}/refund")
    public R<RefundResultVO> refund(@PathVariable String orderNo,
                                    @RequestBody @Valid RefundOrderDTO dto) {
        return R.ok("退票成功", orderService.refund(orderNo, dto));
    }
}
