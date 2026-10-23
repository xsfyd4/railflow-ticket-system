package com.ticket.module.health;

import com.ticket.common.result.R;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基础设施连通性检查：Redis 和 RabbitMQ。
 *
 * <p>和之前的 ping / db-check 是一个思路——先确认"连得上"，再写业务。
 *
 * <p>注意：Redis 和 RabbitMQ 都是**懒连接**的，
 * 所以即使 docker 容器没启动，应用照样能启动；
 * 只有真正调用这个接口时才会报错（并且被 catch 住，返回 fail）。
 */
@RestController
@RequestMapping("/api/public")
public class InfraCheckController {

    /** 健康检查用的队列名 */
    private static final String HEALTH_QUEUE = "ticket.health";

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    /**
     * 注意这里注入的是接口 AmqpAdmin，不是 RabbitAdmin。
     *
     * <p>Spring Boot 自动配置注册的 Bean 声明的类型就是 AmqpAdmin
     * （实际对象是 RabbitAdmin）。按 RabbitAdmin 注入会报
     * "No qualifying bean of type RabbitAdmin available"。
     */
    private final AmqpAdmin amqpAdmin;

    public InfraCheckController(StringRedisTemplate redisTemplate,
                                RabbitTemplate rabbitTemplate,
                                AmqpAdmin amqpAdmin) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    /** GET http://localhost:8080/api/public/infra-check */
    @GetMapping("/infra-check")
    public R<Map<String, Object>> check() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("redis", checkRedis());
        data.put("rabbitmq", checkRabbit());
        return R.ok(data);
    }

    /** 往 Redis 写一个值再读回来，确认读写都通 */
    private String checkRedis() {
        try {
            String key = "ticket:health";
            String value = "ok-" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(30));
            String readBack = redisTemplate.opsForValue().get(key);
            return value.equals(readBack) ? "ok" : "读回来的值和写入的不一致";
        } catch (Exception e) {
            return "fail: " + e.getMessage();
        }
    }

    /** 声明队列并投递一条消息，确认连接和发送都正常 */
    private String checkRabbit() {
        try {
            amqpAdmin.declareQueue(new Queue(HEALTH_QUEUE, true));
            String message = "ping-" + System.currentTimeMillis();
            rabbitTemplate.convertAndSend(HEALTH_QUEUE, message);

            // 立刻读回来，避免每次健康检查都在队列里留一条消息
            Object received = rabbitTemplate.receiveAndConvert(HEALTH_QUEUE, 2000);
            if (!message.equals(received)) {
                return "已发送但没读回，实际收到: " + received;
            }
            return "ok（发送 + 接收都正常）";
        } catch (Exception e) {
            return "fail: " + e.getMessage();
        }
    }
}
