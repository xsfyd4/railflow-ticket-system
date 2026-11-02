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
    private final OrderRequestFlowService flowService;

    public OrderRequestFailureService(OrderRequestMapper requestMapper,
                                      TicketStockCache stockCache,
                                      OrderRequestFlowService flowService) {
        this.requestMapper = requestMapper;
        this.stockCache = stockCache;
        this.flowService = flowService;
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
        if (!flowService.markReleasing(
                requestId, request.getFlowState(),
                safeReason, java.time.Duration.ofSeconds(30))) {
            return false;
        }
        /*
         * 无论数据库是否已经记录 stock_reserved=1，都尝试释放 reservation。
         * Lua 成功但 DB 未来得及更新的崩溃窗口也由这条路径修复。
         */
        stockCache.releaseReservationAfterCommit(
                requestId,
                request.getTravelDate(),
                request.getTrainId(),
                request.getSeatType(),
                request.getFromSegmentIndex(),
                request.getToSegmentIndex(),
                result -> {
                    if (result == TicketStockCache.ReleaseResult.RELEASED
                            || result == TicketStockCache.ReleaseResult.ALREADY_RELEASED
                            || result == TicketStockCache.ReleaseResult.MARKER_MISSING
                            || result == TicketStockCache.ReleaseResult.KEYS_MISSING) {
                        flowService.markStockReleased(requestId);
                    } else if (result == TicketStockCache.ReleaseResult.MANUAL_REVIEW) {
                        flowService.markManualReview(
                                requestId,
                                request.getFlowState(),
                                "Redis reservation 状态异常，需要人工对账");
                    }
                });

        log.warn("异步下单请求失败，requestId={}，reason={}",
                requestId, safeReason);
        return true;
    }
}
