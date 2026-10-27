package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.order.OrderService;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.entity.TicketOrder;
import com.ticket.module.order.mapper.OrderMapper;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 同步下单的幂等入口。
 *
 * <p>为什么不在 {@link OrderServiceImpl#create} 里直接 catch DuplicateKeyException？
 * 因为 create 是一个数据库事务：唯一键冲突会把事务标记为回滚，
 * 在事务内部继续查询重放结果并不可靠。所以把“抢占幂等键”放在事务外，
 * 由本类负责：
 *
 * <ol>
 *   <li>先快速查一次 requestId，命中直接返回已有订单；</li>
 *   <li>未命中时调用事务方法，事务内一次性完成“扣库存 + 写订单”；</li>
 *   <li>并发提交同一个 Key 时，失败的一方会收到 DuplicateKeyException，
 *       事务已经回滚（库存也回滚），此时在事务外读取赢家订单并返回。</li>
 * </ol>
 */
@Service
public class OrderCreateService {

    private final OrderService orderService;
    private final OrderMapper orderMapper;

    public OrderCreateService(OrderService orderService,
                              OrderMapper orderMapper) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
    }

    /**
     * 提交下单。Idempotency-Key 必填，网络超时后用同一个 Key 重试是安全的。
     */
    public CreateOrderVO submit(String idempotencyKey, CreateOrderDTO dto) {
        String requestId = normalizeRequestId(idempotencyKey);
        Long userId = currentUserId();

        // 快速路径：已经下过单，直接返回同一张订单
        TicketOrder existing = orderMapper.findByRequestId(requestId);
        if (existing != null) {
            return replay(existing, userId, dto);
        }

        try {
            return orderService.createWithRequestId(dto, requestId);
        } catch (DuplicateKeyException duplicate) {
            // 并发竞态：另一个请求先提交了同一个幂等键
            TicketOrder winner = orderMapper.findByRequestId(requestId);
            if (winner == null) {
                throw duplicate;
            }
            return replay(winner, userId, dto);
        }
    }

    private CreateOrderVO replay(TicketOrder order,
                                 Long userId,
                                 CreateOrderDTO dto) {
        if (!order.getUserId().equals(userId)) {
            // 幂等键是全局唯一的，别的账号用同一个键不能读到别人的订单
            throw new BizException(ResultCode.ORDER_IDEMPOTENCY_CONFLICT);
        }
        boolean sameRequest =
                order.getTrainId().equals(dto.getTrainId())
                && order.getTravelDate().equals(dto.getTravelDate())
                && order.getFromStationId().equals(dto.getFromStationId())
                && order.getToStationId().equals(dto.getToStationId())
                && order.getSeatType().equals(dto.getSeatType())
                && order.getPassengerCount()
                        .equals(dto.getPassengerIds().size());
        if (!sameRequest) {
            throw new BizException(ResultCode.ORDER_IDEMPOTENCY_CONFLICT);
        }
        return toVO(order);
    }

    private CreateOrderVO toVO(TicketOrder order) {
        CreateOrderVO vo = new CreateOrderVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }

    private String normalizeRequestId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(400,
                    "请求头 Idempotency-Key 不能为空");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 64) {
            throw new BizException(400,
                    "Idempotency-Key 长度不能超过 64 个字符");
        }
        return normalized;
    }

    private Long currentUserId() {
        LoginUser user = LoginUserContext.get();
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return user.getId();
    }
}
