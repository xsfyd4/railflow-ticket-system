package com.ticket.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * cache Redis 的轻量熔断器。
 *
 * <p>连续失败达到阈值后进入 OPEN，冷却期内直接使用 MySQL，
 * 不再为每个请求支付连接超时。冷却结束后自动放行一次探测。
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.CircuitBreaker");

    private final int failureThreshold;
    private final long openMillis;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

    public RedisCircuitBreaker(
            MeterRegistry meterRegistry,
            @Value("${ticket.redis.circuit-breaker.failure-threshold:3}")
            int failureThreshold,
            @Value("${ticket.redis.circuit-breaker.open-seconds:15}")
            long openSeconds) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1, openSeconds) * 1000;
        Gauge.builder("ticket.cache.redis.circuit.open", this,
                        breaker -> breaker.isOpen() ? 1 : 0)
                .register(meterRegistry);
    }

    public boolean isAvailable() {
        long until = openUntil.get();
        if (until == 0) {
            return true;
        }
        if (Instant.now().toEpochMilli() < until) {
            return false;
        }
        openUntil.compareAndSet(until, 0);
        failures.set(0);
        log.info("cache Redis 熔断冷却结束，开始探测");
        return true;
    }

    public boolean isOpen() {
        return openUntil.get() > Instant.now().toEpochMilli();
    }

    public void success() {
        failures.set(0);
        openUntil.set(0);
    }

    public void failure(Throwable error) {
        int current = failures.incrementAndGet();
        if (current < failureThreshold) {
            return;
        }
        long until = Instant.now().toEpochMilli() + openMillis;
        openUntil.set(until);
        failures.set(0);
        log.warn("cache Redis 连续失败 {} 次，熔断 {} 秒：{}",
                current, openMillis / 1000,
                error == null ? "" : error.getMessage());
    }
}
