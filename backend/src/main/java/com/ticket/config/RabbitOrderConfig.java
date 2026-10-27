package com.ticket.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 异步下单的 RabbitMQ 拓扑。
 *
 * <pre>
 * ticket.order.exchange --ticket.order.create--> ticket.order.create
 *          |
 *          +--消费最终失败--> ticket.order.dlx --ticket.order.dead--> ticket.order.dead
 * </pre>
 */
@Configuration
public class RabbitOrderConfig {

    public static final String ORDER_EXCHANGE = "ticket.order.exchange";
    public static final String ORDER_QUEUE = "ticket.order.create";
    public static final String ORDER_ROUTING_KEY = "ticket.order.create";

    public static final String DEAD_EXCHANGE = "ticket.order.dlx";
    public static final String DEAD_QUEUE = "ticket.order.dead";
    public static final String DEAD_ROUTING_KEY = "ticket.order.dead";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderCreateBinding(
            Queue orderCreateQueue,
            DirectExchange orderExchange) {
        return BindingBuilder.bind(orderCreateQueue)
                .to(orderExchange)
                .with(ORDER_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding orderDeadBinding(
            Queue orderDeadQueue,
            DirectExchange orderDeadExchange) {
        return BindingBuilder.bind(orderDeadQueue)
                .to(orderDeadExchange)
                .with(DEAD_ROUTING_KEY);
    }
}
