package com.ticket.module.order.job;

import com.ticket.common.exception.BizException;
import com.ticket.config.RabbitOrderConfig;
import com.ticket.module.order.dto.OrderCreateMessage;
import com.ticket.module.order.impl.OrderRequestFailureService;
import com.ticket.module.order.impl.OrderRequestTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RabbitMQ 下单消费者。
 *
 * <p>业务异常重试固定次数；最终仍失败时把请求标记为失败并释放 Redis 预扣。
 * 无法解析或版本不认识的消息直接拒绝，由 RabbitMQ 路由到死信队列。
 */
@Component
public class OrderCreateConsumer {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.mq.OrderCreateConsumer");

    private final ObjectMapper objectMapper;
    private final OrderRequestTxService requestTxService;
    private final OrderRequestFailureService failureService;
    private final int maxAttempts;
    private final long retryBackoffMs;

    public OrderCreateConsumer(
            ObjectMapper objectMapper,
            OrderRequestTxService requestTxService,
            OrderRequestFailureService failureService,
            @Value("${ticket.order.async.max-attempts:3}") int maxAttempts,
            @Value("${ticket.order.async.retry-backoff-ms:100}")
            long retryBackoffMs) {
        this.objectMapper = objectMapper;
        this.requestTxService = requestTxService;
        this.failureService = failureService;
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    @RabbitListener(queues = RabbitOrderConfig.ORDER_QUEUE)
    public void consume(String body) {
        OrderCreateMessage message;
        try {
            message = objectMapper.readValue(body, OrderCreateMessage.class);
        } catch (Exception e) {
            log.error("异步下单消息 JSON 无法解析，转入死信队列：{}", body, e);
            throw new AmqpRejectAndDontRequeueException(
                    "异步下单消息格式错误", e);
        }

        if (message.version() != OrderCreateMessage.CURRENT_VERSION
                || message.requestId() == null
                || message.requestId().isBlank()) {
            log.error("异步下单消息版本或 requestId 非法：{}", body);
            throw new AmqpRejectAndDontRequeueException(
                    "异步下单消息版本不支持");
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                requestTxService.process(message.requestId());
                return;
            } catch (BizException e) {
                // 业务校验失败不会因为重复执行自行恢复，直接进入补偿。
                lastError = e;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("异步下单消费失败，requestId={}，attempt={}/{}",
                        message.requestId(), attempt, maxAttempts, e);
                if (attempt < maxAttempts) {
                    sleep(retryBackoffMs * attempt);
                }
            }
        }

        String reason = lastError == null
                ? "异步下单处理失败"
                : lastError.getMessage();
        try {
            failureService.fail(message.requestId(), reason);
        } catch (Exception compensationError) {
            /*
             * 补偿本身失败时把消息交还 RabbitMQ，由其重试或最终进入死信队列。
             * order_request 仍保留，恢复任务会继续做库存对账。
             */
            log.error("异步下单失败补偿未完成，requestId={}",
                    message.requestId(), compensationError);
            throw compensationError;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
