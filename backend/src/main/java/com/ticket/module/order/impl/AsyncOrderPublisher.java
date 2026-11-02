package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.config.RabbitOrderConfig;
import com.ticket.module.order.dto.OrderCreateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

/** 把异步下单请求投递到 RabbitMQ。 */
@Component
public class AsyncOrderPublisher {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.mq.AsyncOrderPublisher");

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmTimeoutMs;

    public AsyncOrderPublisher(RabbitTemplate rabbitTemplate,
                               ObjectMapper objectMapper,
                               @Value("${ticket.order.async.publish-confirm-timeout-ms:3000}")
                               long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeoutMs = Math.max(1, confirmTimeoutMs);
    }

    public void publish(String requestId) {
        try {
            String json = objectMapper.writeValueAsString(
                    OrderCreateMessage.of(requestId));
            CorrelationData correlation = new CorrelationData(requestId);
            rabbitTemplate.convertAndSend(
                    RabbitOrderConfig.ORDER_EXCHANGE,
                    RabbitOrderConfig.ORDER_ROUTING_KEY,
                    json,
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.ack()) {
                String reason = confirm == null
                        ? "RabbitMQ 未返回发布确认"
                        : confirm.reason();
                throw new IllegalStateException(reason);
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException(
                        "RabbitMQ 消息无法路由："
                                + correlation.getReturned().getReplyText());
            }
            log.info("异步下单消息已确认，requestId={}", requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待 RabbitMQ 发布确认被中断，requestId={}", requestId, e);
            throw new BizException(ResultCode.ASYNC_MQ_UNAVAILABLE);
        } catch (Exception e) {
            log.error("异步下单消息投递失败，requestId={}", requestId, e);
            throw new BizException(ResultCode.ASYNC_MQ_UNAVAILABLE);
        }
    }
}
