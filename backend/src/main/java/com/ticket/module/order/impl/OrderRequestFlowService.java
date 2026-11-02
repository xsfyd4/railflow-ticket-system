package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
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
    public void create(OrderRequest request) {
        requestMapper.insert(request);
        eventMapper.append(
                request.getRequestId(), null,
                OrderFlowState.CREATED.name(), null, "request-created");
    }

    @Transactional(rollbackFor = Exception.class)
    public String markReserving(String requestId) {
        String ownerToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int affected = requestMapper.markReserving(
                requestId, ownerToken,
                now.plusSeconds(30), now.plusSeconds(30));
        if (affected != 1) {
            throw new BizException(ResultCode.ASYNC_REQUEST_CONFLICT);
        }
        append(requestId, OrderFlowState.CREATED,
                OrderFlowState.RESERVING, ownerToken, "redis-reserve-start");
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
        append(requestId, OrderFlowState.RESERVING,
                OrderFlowState.RESERVED, ownerToken, "redis-reserve-success");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markPublishing(String requestId, Duration retryDelay) {
        int affected = requestMapper.markPublishing(
                requestId, LocalDateTime.now().plus(retryDelay));
        if (affected != 1) {
            return false;
        }
        append(requestId, OrderFlowState.RESERVED,
                OrderFlowState.PUBLISHING, null, "mq-publish-start");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markPublished(String requestId) {
        int affected = requestMapper.markPublished(
                requestId, LocalDateTime.now());
        if (affected != 1) {
            return false;
        }
        append(requestId, OrderFlowState.PUBLISHING,
                OrderFlowState.PUBLISHED, null, "mq-publish-confirmed");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markProcessing(String requestId, String fromState) {
        int affected = requestMapper.markProcessing(requestId);
        if (affected != 1) {
            return false;
        }
        append(requestId, parse(fromState),
                OrderFlowState.PROCESSING, null, "consumer-start");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markSuccess(String requestId,
                               Long orderId,
                               String orderNo) {
        int affected = requestMapper.markSuccess(
                requestId, orderId, orderNo);
        if (affected != 1) {
            return false;
        }
        append(requestId, OrderFlowState.PROCESSING,
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
