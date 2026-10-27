package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.order.OrderService;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.entity.OrderRequest;
import com.ticket.module.order.mapper.OrderRequestMapper;
import com.ticket.module.order.vo.CreateOrderVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * MQ 消费者真正落单的事务边界。
 *
 * <p>order_request 行锁、MySQL 分段库存条件扣减、订单主表、订单明细
 * 以及请求最终状态必须一起提交或一起回滚。
 */
@Service
public class OrderRequestTxService {

    private static final Logger log =
            LoggerFactory.getLogger(OrderRequestTxService.class);

    private static final int STATUS_SUCCESS = 3;
    private static final int STATUS_FAILED = 4;

    private final OrderRequestMapper requestMapper;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public OrderRequestTxService(OrderRequestMapper requestMapper,
                                 OrderService orderService,
                                 ObjectMapper objectMapper) {
        this.requestMapper = requestMapper;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    /**
     * 重复消息安全：同一 requestId 已成功或失败时直接返回；
     * 两个消费者并发处理同一消息时，FOR UPDATE 让它们串行进入，
     * 后进入者会看到已经变成 3，不会创建第二张订单。
     */
    @Transactional(rollbackFor = Exception.class)
    public void process(String requestId) {
        OrderRequest request =
                requestMapper.findByRequestIdForUpdate(requestId);
        if (request == null) {
            throw new BizException(ResultCode.ASYNC_REQUEST_NOT_FOUND);
        }
        if (request.getStatus() == STATUS_SUCCESS
                || request.getStatus() == STATUS_FAILED) {
            log.info("异步下单消息重复到达，当前已终态，requestId={}，status={}",
                    requestId, request.getStatus());
            return;
        }
        if (request.getStatus() != 1 && request.getStatus() != 2) {
            throw new BizException(400,
                    "异步下单请求状态不允许消费：" + request.getStatus());
        }

        requestMapper.markProcessing(requestId);

        CreateOrderDTO dto;
        try {
            dto = objectMapper.readValue(
                    request.getPayloadJson(), CreateOrderDTO.class);
        } catch (Exception e) {
            throw new BizException(400, "异步下单参数快照无法解析");
        }

        CreateOrderVO order =
                orderService.createForUser(request.getUserId(), dto);
        int affected = requestMapper.markSuccess(
                requestId, order.getOrderId(), order.getOrderNo());
        if (affected != 1) {
            /*
             * 抛出后同一事务会撤销库存和订单，由消费者补偿 Redis。
             * 正常不会发生，保留是为了防止状态表被人工改坏。
             */
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        log.info("异步订单消费成功，requestId={}，orderNo={}",
                requestId, order.getOrderNo());
    }
}
