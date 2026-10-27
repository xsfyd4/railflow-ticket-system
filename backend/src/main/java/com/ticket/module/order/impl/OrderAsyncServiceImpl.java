package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.entity.OrderRequest;
import com.ticket.module.order.mapper.OrderMapper;
import com.ticket.module.order.mapper.OrderRequestMapper;
import com.ticket.module.order.vo.AsyncOrderVO;
import com.ticket.module.order.vo.OrderRouteVO;
import com.ticket.module.passenger.entity.Passenger;
import com.ticket.module.passenger.mapper.PassengerMapper;
import com.ticket.module.stock.TicketStockCache;
import com.ticket.module.train.vo.TrainPriceVO;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;

/**
 * 异步下单入口。
 *
 * <p>提交阶段只做校验、持久化请求和 Redis 预扣，不在 HTTP 线程里等待订单落库；
 * 真正的 MySQL 订单由 RabbitMQ 消费者完成。
 */
@Service
public class OrderAsyncServiceImpl {

    private static final int STATUS_SUBMITTED = 0;
    private static final int STATUS_QUEUED = 1;

    private final OrderRequestMapper requestMapper;
    private final OrderMapper orderMapper;
    private final PassengerMapper passengerMapper;
    private final TicketStockCache stockCache;
    private final AsyncOrderPublisher publisher;
    private final OrderRequestFailureService failureService;
    private final ObjectMapper objectMapper;

    public OrderAsyncServiceImpl(
            OrderRequestMapper requestMapper,
            OrderMapper orderMapper,
            PassengerMapper passengerMapper,
            TicketStockCache stockCache,
            AsyncOrderPublisher publisher,
            OrderRequestFailureService failureService,
            ObjectMapper objectMapper) {
        this.requestMapper = requestMapper;
        this.orderMapper = orderMapper;
        this.passengerMapper = passengerMapper;
        this.stockCache = stockCache;
        this.publisher = publisher;
        this.failureService = failureService;
        this.objectMapper = objectMapper;
    }

    public AsyncOrderVO submit(String idempotencyKey, CreateOrderDTO dto) {
        Long userId = currentUserId();
        String requestId = normalizeRequestId(idempotencyKey);
        RequestPlan plan = validateAndBuildPlan(userId, dto);

        OrderRequest existing = requestMapper.findByRequestId(requestId);
        if (existing != null) {
            return replayExisting(existing, userId, plan);
        }

        OrderRequest request = buildRequest(requestId, userId, dto, plan);
        try {
            requestMapper.insert(request);
        } catch (DuplicateKeyException duplicate) {
            // 两个相同幂等键并发提交时，只有一个能 INSERT 成功。
            OrderRequest winner = requestMapper.findByRequestId(requestId);
            if (winner == null) {
                throw duplicate;
            }
            return replayExisting(winner, userId, plan);
        }

        TicketStockCache.ReserveResult reserveResult =
                stockCache.tryReserve(
                        dto.getTravelDate(),
                        dto.getTrainId(),
                        dto.getSeatType(),
                        plan.fromSegmentIndex(),
                        plan.toSegmentIndex(),
                        plan.passengerCount());

        if (reserveResult == TicketStockCache.ReserveResult.UNAVAILABLE) {
            failureService.fail(requestId,
                    ResultCode.ASYNC_REDIS_UNAVAILABLE.getMsg());
            throw new BizException(ResultCode.ASYNC_REDIS_UNAVAILABLE);
        }
        if (reserveResult == TicketStockCache.ReserveResult.NOT_ENOUGH) {
            failureService.fail(requestId,
                    ResultCode.TICKET_NOT_ENOUGH.getMsg());
            throw new BizException(ResultCode.TICKET_NOT_ENOUGH);
        }

        int queued = requestMapper.markQueued(requestId);
        if (queued != 1) {
            throw new BizException(ResultCode.ASYNC_REQUEST_CONFLICT);
        }

        try {
            publisher.publish(requestId);
            requestMapper.markPublished(requestId, LocalDateTime.now());
        } catch (RuntimeException e) {
            failureService.fail(requestId, e.getMessage());
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(ResultCode.ASYNC_MQ_UNAVAILABLE);
        }

        return toVO(requestMapper.findByRequestIdAndUserId(
                requestId, userId));
    }

    public AsyncOrderVO status(String requestId) {
        String normalized = normalizeRequestId(requestId);
        Long userId = currentUserId();
        OrderRequest request = requestMapper.findByRequestIdAndUserId(
                normalized, userId);
        if (request == null) {
            throw new BizException(ResultCode.ASYNC_REQUEST_NOT_FOUND);
        }
        return toVO(request);
    }

    private AsyncOrderVO replayExisting(OrderRequest existing,
                                        Long userId,
                                        RequestPlan plan) {
        if (!existing.getUserId().equals(userId)) {
            throw new BizException(ResultCode.ASYNC_REQUEST_CONFLICT);
        }
        boolean sameRequest =
                existing.getTrainId().equals(plan.trainId())
                && existing.getTravelDate().equals(plan.travelDate())
                && existing.getSeatType().equals(plan.seatType())
                && existing.getFromSegmentIndex()
                        .equals(plan.fromSegmentIndex())
                && existing.getToSegmentIndex()
                        .equals(plan.toSegmentIndex())
                && existing.getPassengerCount()
                        .equals(plan.passengerCount());
        if (!sameRequest) {
            throw new BizException(ResultCode.ASYNC_REQUEST_CONFLICT);
        }
        return toVO(existing);
    }

    private RequestPlan validateAndBuildPlan(Long userId,
                                             CreateOrderDTO dto) {
        if (dto.getFromStationId().equals(dto.getToStationId())) {
            throw new BizException(400, "出发站和到达站不能相同");
        }
        if (dto.getTravelDate() == null
                || dto.getTravelDate().isBefore(LocalDate.now())) {
            throw new BizException(400, "乘车日期不能早于今天");
        }
        if (dto.getPassengerIds() == null
                || dto.getPassengerIds().isEmpty()
                || dto.getPassengerIds().size() > 5) {
            throw new BizException(400, "一次需要选择 1 到 5 位乘车人");
        }
        if (new HashSet<>(dto.getPassengerIds()).size()
                != dto.getPassengerIds().size()) {
            throw new BizException(400, "同一个乘车人不能重复购票");
        }

        List<Passenger> passengers =
                passengerMapper.findByIdsAndUserId(
                        dto.getPassengerIds(), userId);
        if (passengers.size() != dto.getPassengerIds().size()) {
            throw new BizException(ResultCode.PASSENGER_NOT_FOUND);
        }

        OrderRouteVO route = orderMapper.findRoute(
                dto.getTrainId(),
                dto.getFromStationId(),
                dto.getToStationId());
        if (route == null) {
            throw new BizException(ResultCode.TRAIN_NOT_FOUND);
        }
        int fromSegmentIndex = route.getFromStopOrder();
        int toSegmentIndex = route.getToStopOrder() - 1;
        int segmentCount = toSegmentIndex - fromSegmentIndex + 1;
        List<TrainPriceVO> prices = orderMapper.findPrices(
                dto.getTrainId(),
                dto.getSeatType(),
                fromSegmentIndex,
                toSegmentIndex);
        if (prices.size() != segmentCount) {
            throw new BizException(ResultCode.ORDER_PRICE_CONFIG_ERROR);
        }
        for (int i = 0; i < prices.size(); i++) {
            TrainPriceVO price = prices.get(i);
            if (!price.getSegmentIndex().equals(fromSegmentIndex + i)
                    || price.getPrice() == null
                    || price.getPrice().signum() < 0) {
                throw new BizException(ResultCode.ORDER_PRICE_CONFIG_ERROR);
            }
        }

        return new RequestPlan(
                dto.getTrainId(),
                dto.getTravelDate(),
                dto.getSeatType(),
                fromSegmentIndex,
                toSegmentIndex,
                dto.getPassengerIds().size());
    }

    private OrderRequest buildRequest(String requestId,
                                      Long userId,
                                      CreateOrderDTO dto,
                                      RequestPlan plan) {
        OrderRequest request = new OrderRequest();
        request.setRequestId(requestId);
        request.setUserId(userId);
        try {
            request.setPayloadJson(
                    objectMapper.writeValueAsString(dto));
        } catch (Exception e) {
            throw new BizException(400, "下单参数无法序列化");
        }
        request.setTrainId(plan.trainId());
        request.setTravelDate(plan.travelDate());
        request.setSeatType(plan.seatType());
        request.setFromSegmentIndex(plan.fromSegmentIndex());
        request.setToSegmentIndex(plan.toSegmentIndex());
        request.setPassengerCount(plan.passengerCount());
        request.setStatus(STATUS_SUBMITTED);
        request.setStockReserved(0);
        return request;
    }

    private AsyncOrderVO toVO(OrderRequest request) {
        AsyncOrderVO vo = new AsyncOrderVO();
        vo.setRequestId(request.getRequestId());
        vo.setStatus(request.getStatus());
        vo.setStatusText(statusText(request.getStatus()));
        vo.setOrderId(request.getOrderId());
        vo.setOrderNo(request.getOrderNo());
        vo.setFailReason(request.getFailReason());
        vo.setCreatedAt(request.getCreatedAt());
        vo.setUpdatedAt(request.getUpdatedAt());
        return vo;
    }

    private String statusText(Integer status) {
        return switch (status) {
            case 0 -> "已提交";
            case 1 -> "已预扣库存，等待处理";
            case 2 -> "处理中";
            case 3 -> "下单成功";
            case 4 -> "下单失败";
            default -> "未知状态";
        };
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(400,
                    "请求头 Idempotency-Key 不能为空");
        }
        String normalized = requestId.trim();
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

    private record RequestPlan(Long trainId,
                               LocalDate travelDate,
                               Integer seatType,
                               int fromSegmentIndex,
                               int toSegmentIndex,
                               int passengerCount) {
    }
}
