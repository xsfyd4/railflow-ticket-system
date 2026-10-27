package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.config.RabbitOrderConfig;
import com.ticket.module.order.dto.OrderCreateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 把异步下单请求投递到 RabbitMQ。 */
@Component
public class AsyncOrderPublisher {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.mq.AsyncOrderPublisher");

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public AsyncOrderPublisher(RabbitTemplate rabbitTemplate,
                               ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String requestId) {
        try {
            String json = objectMapper.writeValueAsString(
                    OrderCreateMessage.of(requestId));
            rabbitTemplate.convertAndSend(
                    RabbitOrderConfig.ORDER_EXCHANGE,
                    RabbitOrderConfig.ORDER_ROUTING_KEY,
                    json);
            log.info("异步下单消息已投递，requestId={}", requestId);
        } catch (Exception e) {
            log.error("异步下单消息投递失败，requestId={}", requestId, e);
            throw new BizException(ResultCode.ASYNC_MQ_UNAVAILABLE);
        }
    }
}
