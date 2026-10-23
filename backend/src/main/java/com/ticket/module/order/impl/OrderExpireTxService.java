package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.order.entity.TicketOrder;
import com.ticket.module.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 处理单个过期订单。
 *
 * <p>定时任务和用户支付/取消可能同时操作同一订单，所以这里必须重新加行锁，
 * 而不是直接相信扫描时查到的 status=0。
 */
@Service
public class OrderExpireTxService {

    private static final int STATUS_PENDING_PAYMENT = 0;

    private final OrderMapper orderMapper;

    public OrderExpireTxService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * @return 本次确实取消了订单返回 true；订单已支付、已取消或尚未过期返回 false
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean expireOne(Long orderId) {
        TicketOrder order = orderMapper.findByIdForUpdate(orderId);
        if (order == null || order.getStatus() != STATUS_PENDING_PAYMENT) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        if (order.getExpireTime() == null
                || order.getExpireTime().isAfter(now)) {
            return false;
        }

        int segmentCount =
                order.getToSegmentIndex() - order.getFromSegmentIndex() + 1;
        int stockAffected = orderMapper.restoreStock(
                order.getTravelDate(),
                order.getTrainId(),
                order.getSeatType(),
                order.getFromSegmentIndex(),
                order.getToSegmentIndex(),
                order.getPassengerCount());
        if (stockAffected != segmentCount) {
            throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
        }

        int orderAffected = orderMapper.markCancelled(order.getId(), now);
        if (orderAffected != 1) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        return true;
    }
}
