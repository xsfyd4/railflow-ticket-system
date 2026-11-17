package com.ticket.module.order.impl;

import com.ticket.module.order.OrderFlowState;
import com.ticket.module.order.entity.OrderRequest;
import com.ticket.module.order.mapper.OrderFlowEventMapper;
import com.ticket.module.order.mapper.OrderRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 异步下单状态机。
 *
 * <p>状态更新和流程流水在同一事务内完成，恢复任务可以按 event_id
 * 增量扫描，也可以按 order_request.id 定期全量审计。
 */
@Service
public class OrderRequestFlowService {

    private final OrderRequestMapper requestMapper;
    private final OrderFlowEventMapper eventMapper;

    public OrderRequestFlowService(OrderRequestMapper requestMapper,
                                   OrderFlowEventMapper eventMapper) {
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(OrderRequest request) {
        String ownerToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        request.setOwnerToken(ownerToken);
        request.setLeaseUntil(now.plusSeconds(30));
        request.setNextRetryAt(now.plusSeconds(30));
        requestMapper.insert(request);
        eventMapper.append(
                request.getRequestId(), null,
                OrderFlowState.CREATED.name(), null, "request-created");
        return ownerToken;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markReserved(String requestId,
                                String ownerToken,
                                Duration retryDelay) {
        int affected = requestMapper.markReserved(
                requestId, ownerToken,
                LocalDateTime.now().plus(retryDelay));
        if (affected != 1) {
            return false;
        }
        append(requestId, OrderFlowState.CREATED,
                OrderFlowState.RESERVED, ownerToken, "redis-reserve-success");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markPublished(String requestId) {
        int affected = requestMapper.markPublished(
                requestId, LocalDateTime.now());
        if (affected != 1) {
            return false;
        }
        append(requestId, OrderFlowState.RESERVED,
                OrderFlowState.PUBLISHED, null, "mq-publish-confirmed");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deferPublishing(String requestId,
                                   String error,
                                   Duration retryDelay) {
        int affected = requestMapper.deferPublishing(
                requestId, error,
                LocalDateTime.now().plus(retryDelay));
        if (affected != 1) {
            return false;
        }
        append(requestId, OrderFlowState.RESERVED,
                OrderFlowState.RESERVED, null, "mq-publish-retry");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markSuccess(String requestId,
                               String fromState,
                               Long orderId,
                               String orderNo) {
        int affected = requestMapper.markSuccess(
                requestId, fromState, orderId, orderNo);
        if (affected != 1) {
            return false;
        }
        append(requestId, parse(fromState),
                OrderFlowState.SUCCEEDED, null, orderNo);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markReleasing(String requestId,
                                 String fromState,
                                 String reason,
                                 Duration retryDelay) {
        int affected = requestMapper.markReleasing(
                requestId, reason,
                LocalDateTime.now().plus(retryDelay));
        if (affected != 1) {
            return false;
        }
        append(requestId, parse(fromState), OrderFlowState.RELEASING,
                null, reason);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markStockReleased(String requestId) {
        int affected = requestMapper.markStockReleased(requestId);
        if (affected != 1) {
            return false;
        }
        append(requestId, OrderFlowState.RELEASING,
                OrderFlowState.FAILED, null, "redis-stock-released");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markManualReview(String requestId,
                                    String fromState,
                                    String reason) {
        int affected = requestMapper.markManualReview(requestId, reason);
        if (affected != 1) {
            return false;
        }
        append(requestId, parse(fromState),
                OrderFlowState.MANUAL_REVIEW, null, reason);
        return true;
    }

    private void append(String requestId,
                        OrderFlowState from,
                        OrderFlowState to,
                        String ownerToken,
                        String detail) {
        eventMapper.append(
                requestId,
                from == null ? null : from.name(),
                to.name(), ownerToken, detail);
    }

    private OrderFlowState parse(String value) {
        try {
            return OrderFlowState.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
