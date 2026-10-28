package com.ticket.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.clients.jedis.JedisPoolConfig;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Redis 主写连接。
 *
 * <p>所有库存、版本、UUID 和缓存写入都通过这个模板。
 * 查票副本读取由 {@link ReplicaRedisReader} 单独轮询两个副本完成。
 */
@Configuration
@EnableConfigurationProperties(TicketRedisProperties.class)
public class TicketRedisConfig {

    @Bean
    @Primary
    public JedisConnectionFactory redisConnectionFactory(
            TicketRedisProperties properties) {
        RedisConfiguration configuration = redisConfiguration(properties);
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(64);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);

        JedisConnectionFactory factory;
        if (configuration instanceof RedisSentinelConfiguration sentinel) {
            factory = new JedisConnectionFactory(
                    sentinel, poolConfig);
        } else {
            factory = new JedisConnectionFactory(
                    (RedisStandaloneConfiguration) configuration);
            factory.setPoolConfig(poolConfig);
        }
        factory.setClientName("train-ticket-redis-writer");
        factory.setUsePool(true);
        factory.setTimeout((int) Math.max(
                200, properties.getCommandTimeoutMs()));
        return factory;
    }

    @Bean
    @Primary
    public StringRedisTemplate writeRedisTemplate(
            JedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private RedisConfiguration redisConfiguration(
            TicketRedisProperties properties) {
        if (!properties.isSentinelMode()) {
            RedisStandaloneConfiguration standalone =
                    new RedisStandaloneConfiguration(
                            properties.getHost(), properties.getPort());
            applyPassword(standalone, properties.getPassword());
            return standalone;
        }

        if (properties.getSentinelNodes().isEmpty()) {
            throw new IllegalArgumentException(
                    "Sentinel 模式必须配置 ticket.redis.sentinel-nodes");
        }
        RedisSentinelConfiguration sentinel =
                new RedisSentinelConfiguration();
        sentinel.master(properties.getMaster());
        Set<RedisNode> nodes = new LinkedHashSet<>();
        for (String address : properties.getSentinelNodes()) {
            nodes.add(RedisNode.fromString(address.trim()));
        }
        sentinel.setSentinels(nodes);
        applyPassword(sentinel, properties.getPassword());
        return sentinel;
    }

    private void applyPassword(RedisStandaloneConfiguration configuration,
                               String password) {
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
    }

    private void applyPassword(RedisSentinelConfiguration configuration,
                               String password) {
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
    }
}
