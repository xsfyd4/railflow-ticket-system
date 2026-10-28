package com.ticket.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCircuitBreakerTest {

    @Test
    void shouldOpenAfterFailuresAndAllowProbeAfterCooldown()
            throws InterruptedException {
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(
                new SimpleMeterRegistry(), 2, 1);

        assertTrue(breaker.isAvailable());
        breaker.failure(new IllegalStateException("first"));
        assertTrue(breaker.isAvailable());
        breaker.failure(new IllegalStateException("second"));
        assertFalse(breaker.isAvailable());
        assertTrue(breaker.isOpen());

        Thread.sleep(1100);
        assertTrue(breaker.isAvailable());
    }
}
