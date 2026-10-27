package com.ticket.module.order;

import com.ticket.common.result.R;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.impl.OrderAsyncServiceImpl;
import com.ticket.module.order.vo.AsyncOrderVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redis 预扣 + RabbitMQ 削峰的异步下单接口。
 *
 * <p>原有同步接口 {@code POST /api/user/orders} 继续保留，方便对照学习和回退。
 */
@RestController
@RequestMapping("/api/user/orders/async")
public class AsyncOrderController {

    private final OrderAsyncServiceImpl asyncOrderService;

    public AsyncOrderController(OrderAsyncServiceImpl asyncOrderService) {
        this.asyncOrderService = asyncOrderService;
    }

    /**
     * POST /api/user/orders/async
     *
     * <p>必须带 Idempotency-Key。网络超时后用同一个 Key 重试，
     * 不会重复扣库存或生成第二张订单。
     */
    @PostMapping
    public R<AsyncOrderVO> submit(
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @RequestBody @Valid CreateOrderDTO dto) {
        return R.ok("已进入下单队列",
                asyncOrderService.submit(idempotencyKey, dto));
    }

    /** GET /api/user/orders/async/{requestId} */
    @GetMapping("/{requestId}")
    public R<AsyncOrderVO> status(@PathVariable String requestId) {
        return R.ok(asyncOrderService.status(requestId));
    }
}
