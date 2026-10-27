package com.ticket.module.order.impl;

import com.ticket.module.order.entity.OrderRequest;
import com.ticket.module.order.mapper.OrderRequestMapper;
import com.ticket.module.stock.TicketStockCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异步请求最终失败的补偿边界。
 *
 * <p>先锁 order_request，只有第一次能把状态改成 4 的线程负责回补 Redis，
 * 因此重复消息、重试线程和恢复任务不会重复加票。
 */
@Service
public class OrderRequestFailureService {

    private static final Logger log =
            LoggerFactory.getLogger(OrderRequestFailureService.class);

    private final OrderRequestMapper requestMapper;
    private final TicketStockCache stockCache;

    public OrderRequestFailureService(OrderRequestMapper requestMapper,
                                      TicketStockCache stockCache) {
        this.requestMapper = requestMapper;
        this.stockCache = stockCache;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean fail(String requestId, String reason) {
        OrderRequest request =
                requestMapper.findByRequestIdForUpdate(requestId);
        if (request == null || request.getStatus() == 3
                || request.getStatus() == 4) {
            return false;
        }

        String safeReason = reason == null || reason.isBlank()
                ? "异步下单处理失败" : reason;
        int affected = requestMapper.markFailed(requestId, safeReason);
        if (affected != 1) {
            return false;
        }

        if (request.getStockReserved() != null
                && request.getStockReserved() == 1) {
            stockCache.incrementAfterCommit(
                    request.getTravelDate(),
                    request.getTrainId(),
                    request.getSeatType(),
                    request.getFromSegmentIndex(),
                    request.getToSegmentIndex(),
                    request.getPassengerCount());
        } else {
            /*
             * status=0 时理论上还没预扣；如果进程恰好死在 Lua 成功与
             * stock_reserved=1 之间，Redis 可能多扣了。用 MySQL 值覆盖
             * 这段区间，修复这个极小的崩溃窗口。
             */
            stockCache.refreshAfterCommit(
                    request.getTravelDate(),
                    request.getTrainId(),
                    request.getSeatType(),
                    request.getFromSegmentIndex(),
                    request.getToSegmentIndex());
        }

        log.warn("异步下单请求失败，requestId={}，reason={}",
                requestId, safeReason);
        return true;
    }
}
