package com.ticket.module.order.job;

import com.ticket.module.order.OrderFlowState;
import com.ticket.module.order.entity.OrderFlowEvent;
import com.ticket.module.order.entity.OrderRequest;
import com.ticket.module.order.impl.AsyncOrderPublisher;
import com.ticket.module.order.impl.OrderRequestFailureService;
import com.ticket.module.order.impl.OrderRequestFlowService;
import com.ticket.module.order.mapper.MaintenanceCursorMapper;
import com.ticket.module.order.mapper.OrderFlowEventMapper;
import com.ticket.module.order.mapper.OrderRequestMapper;
import com.ticket.module.stock.TicketStockCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步下单双模式恢复任务。
 *
 * <p>短周期按 order_flow_event.event_id 增量处理；低频按
 * order_request.id 游标全量审计，修复遗漏事件和卡死状态。
 */
@Component
public class OrderRequestRecoveryJob {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.mq.OrderRequestRecoveryJob");
    private static final String JOB_NAME = "ORDER_FLOW";

    private final OrderRequestMapper requestMapper;
    private final OrderFlowEventMapper eventMapper;
    private final MaintenanceCursorMapper cursorMapper;
    private final AsyncOrderPublisher publisher;
    private final OrderRequestFailureService failureService;
    private final OrderRequestFlowService flowService;
    private final TicketStockCache stockCache;
    private final int batchSize;
    private final long eventDelaySeconds;
    private final long staleMinutes;
    private final int fullBatchesPerRun;

    public OrderRequestRecoveryJob(
            OrderRequestMapper requestMapper,
            OrderFlowEventMapper eventMapper,
            MaintenanceCursorMapper cursorMapper,
            AsyncOrderPublisher publisher,
            OrderRequestFailureService failureService,
            OrderRequestFlowService flowService,
            TicketStockCache stockCache,
            @Value("${ticket.order.async.recovery-batch-size:100}")
            int batchSize,
            @Value("${ticket.order.async.event-delay-seconds:10}")
            long eventDelaySeconds,
            @Value("${ticket.order.async.stale-minutes:10}")
            long staleMinutes,
            @Value("${ticket.order.async.full-batches-per-run:20}")
            int fullBatchesPerRun) {
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
        this.cursorMapper = cursorMapper;
        this.publisher = publisher;
        this.failureService = failureService;
        this.flowService = flowService;
        this.stockCache = stockCache;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.eventDelaySeconds = Math.max(1, eventDelaySeconds);
        this.staleMinutes = Math.max(1, staleMinutes);
        this.fullBatchesPerRun = Math.max(1, fullBatchesPerRun);
    }

    @Scheduled(
            scheduler = "orderTaskScheduler",
            fixedDelayString = "${ticket.order.async.recovery-scan-ms:30000}",
            initialDelayString =
                    "${ticket.order.async.recovery-initial-delay-ms:30000}"
    )
    public void recoverByEvents() {
        long lastEventId = valueOrZero(
                cursorMapper.findLastEventId(JOB_NAME));
        List<OrderFlowEvent> events = eventMapper.findDueAfter(
                lastEventId,
                LocalDateTime.now().minusSeconds(eventDelaySeconds),
                batchSize);
        for (OrderFlowEvent event : events) {
            try {
                repairRequest(event.getRequestId());
            } catch (Exception e) {
                log.error("流程流水恢复失败，eventId={}，requestId={}",
                        event.getEventId(), event.getRequestId(), e);
            }
            // 单条异常不阻塞后续事件，全量扫描会继续兜底。
            cursorMapper.updateLastEventId(
                    JOB_NAME, event.getEventId());
        }
    }

    @Scheduled(
            scheduler = "orderTaskScheduler",
            fixedDelayString =
                    "${ticket.order.async.full-scan-ms:600000}",
            initialDelayString =
                    "${ticket.order.async.full-scan-initial-delay-ms:120000}"
    )
    public void fullAudit() {
        long lastId = valueOrZero(cursorMapper.findFullLastId(JOB_NAME));
        for (int batch = 0; batch < fullBatchesPerRun; batch++) {
            List<OrderRequest> requests =
                    requestMapper.findRecoverablePage(lastId, batchSize);
            if (requests.isEmpty()) {
                cursorMapper.updateFullLastId(JOB_NAME, 0L);
                return;
            }
            for (OrderRequest request : requests) {
                try {
                    repairRequest(request.getRequestId());
                } catch (Exception e) {
                    log.error("流程全量审计失败，requestId={}",
                            request.getRequestId(), e);
                }
            }
            lastId = requests.get(requests.size() - 1).getId();
            cursorMapper.updateFullLastId(JOB_NAME, lastId);
            if (requests.size() < batchSize) {
                cursorMapper.updateFullLastId(JOB_NAME, 0L);
                return;
            }
        }
    }

    private void repairRequest(String requestId) {
        OrderRequest request = requestMapper.findByRequestId(requestId);
        if (request == null || request.getStatus() == null) {
            return;
        }
        OrderFlowState state = parseFlowState(request.getFlowState());
        if (state == OrderFlowState.SUCCEEDED
                || state == OrderFlowState.MANUAL_REVIEW) {
            return;
        }
        if (request.getStatus() == 4
                && Integer.valueOf(1).equals(request.getStockReleased())) {
            return;
        }
        if (request.getStatus() == 4
                || request.getStatus() == 5
                || state == OrderFlowState.RELEASING) {
            compensate(request);
            return;
        }
        if (!isDue(request, state)) {
            return;
        }
        switch (state) {
            case CREATED -> {
                if (stockCache.reservationExists(
                        requestId,
                        request.getTravelDate(),
                        request.getTrainId())) {
                    if (flowService.markReserved(
                            requestId,
                            request.getOwnerToken(),
                            Duration.ofSeconds(10))) {
                        OrderRequest updated =
                                requestMapper.findByRequestId(requestId);
                        publishReserved(updated);
                    }
                } else {
                    failureService.fail(
                            requestId,
                            "Redis 预扣结果不存在，已按 MySQL 对账");
                }
            }
            case RESERVED -> publishReserved(request);
            case PUBLISHED -> republishIfStale(request);
            case RELEASING, FAILED, SUCCEEDED, MANUAL_REVIEW -> {
                // 终态不重复处理。
            }
        }
    }

    private void publishReserved(OrderRequest request) {
        if (request == null || request.getStatus() == null
                || request.getStatus() == 4) {
            return;
        }
        try {
            publisher.publish(request.getRequestId());
        } catch (Exception e) {
            flowService.deferPublishing(
                    request.getRequestId(),
                    e.getMessage(),
                    Duration.ofSeconds(5));
            log.warn("恢复任务投递仍失败，requestId={}",
                    request.getRequestId());
            return;
        }
        if (!flowService.markPublished(request.getRequestId())) {
            OrderRequest latest =
                    requestMapper.findByRequestId(request.getRequestId());
            if (latest != null && latest.getStatus() != null
                    && (latest.getStatus() == 3
                    || latest.getStatus() == 4)) {
                return;
            }
            throw new IllegalStateException(
                    "RabbitMQ 已确认，但流程状态无法标记为 PUBLISHED");
        }
    }

    private void republishIfStale(OrderRequest request) {
        if (request.getUpdatedAt() == null
                || request.getUpdatedAt().isAfter(
                        LocalDateTime.now().minusMinutes(staleMinutes))) {
            return;
        }
        publisher.publish(request.getRequestId());
        log.warn("重复投递超时未消费的异步订单，requestId={}",
                request.getRequestId());
    }

    private void compensate(OrderRequest request) {
        TicketStockCache.ReleaseResult result =
                stockCache.releaseReservationNow(
                        request.getRequestId(),
                        request.getTravelDate(),
                        request.getTrainId(),
                        request.getSeatType(),
                        request.getFromSegmentIndex(),
                        request.getToSegmentIndex());
        if (result == TicketStockCache.ReleaseResult.RELEASED
                || result == TicketStockCache.ReleaseResult.ALREADY_RELEASED
                || result == TicketStockCache.ReleaseResult.MARKER_MISSING
                || result == TicketStockCache.ReleaseResult.KEYS_MISSING) {
            flowService.markStockReleased(request.getRequestId());
            return;
        }
        if (result == TicketStockCache.ReleaseResult.MANUAL_REVIEW) {
            flowService.markManualReview(
                    request.getRequestId(),
                    request.getFlowState(),
                    "Redis reservation 状态异常，需要人工对账");
        }
    }

    private boolean isDue(OrderRequest request, OrderFlowState state) {
        LocalDateTime now = LocalDateTime.now();
        if (state == OrderFlowState.RESERVED) {
            return request.getNextRetryAt() == null
                    || !request.getNextRetryAt().isAfter(now);
        }
        return request.getUpdatedAt() == null
                || request.getUpdatedAt().isBefore(
                        now.minusMinutes(staleMinutes));
    }

    private OrderFlowState parseFlowState(String value) {
        try {
            return OrderFlowState.valueOf(value);
        } catch (Exception ignored) {
            return OrderFlowState.MANUAL_REVIEW;
        }
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
