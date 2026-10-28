package com.ticket.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import redis.clients.jedis.JedisPoolConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LazyJedisConnectionFactoryTest {

    @Test
    void shouldResetFailedStartupStateAndAllowAnotherAttempt() {
        RedisSentinelConfiguration configuration =
                new RedisSentinelConfiguration()
                        .master("unreachable-master")
                        .sentinel("127.0.0.1", 1);
        LazyJedisConnectionFactory factory =
                new LazyJedisConnectionFactory(
                        configuration, new JedisPoolConfig());
        factory.afterPropertiesSet();

        RuntimeException first = assertThrows(
                RuntimeException.class, factory::getConnection);
        assertFalse(factory.isRunning());

        RuntimeException second = assertThrows(
                RuntimeException.class, factory::getConnection);
        assertFalse(factory.isRunning());
        String message = String.valueOf(second.getMessage());
        assertFalse(message.contains("STARTING"),
                "失败后应允许再次初始化，不能卡在 STARTING");
        // 第二次仍然失败是预期的，关键是状态已复位，可再次尝试启动。
    }
}
