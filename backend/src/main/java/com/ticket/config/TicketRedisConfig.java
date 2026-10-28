package com.ticket.config;

import org.springframework.beans.factory.annotation.Qualifier;
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
 * 两套物理 Redis 连接。
 *
 * <p>默认主模板连接 stock Master，以兼容已有库存和 UUID 代码；
 * 查票缓存必须显式使用 cache 模板，读写分别落在 cache 主从组。
 */
@Configuration
@EnableConfigurationProperties(TicketRedisProperties.class)
public class TicketRedisConfig {

    @Bean
    @Primary
    public JedisConnectionFactory stockRedisConnectionFactory(
            TicketRedisProperties properties) {
        return connectionFactory(
                properties.getStock(),
                properties.getCommandTimeoutMs(),
                "train-ticket-stock-writer");
    }

    @Bean
    public JedisConnectionFactory cacheRedisConnectionFactory(
            TicketRedisProperties properties) {
        return connectionFactory(
                properties.getCache(),
                properties.getCommandTimeoutMs(),
                "train-ticket-cache-writer");
    }

    @Bean
    @Primary
    public StringRedisTemplate writeRedisTemplate(
            @Qualifier("stockRedisConnectionFactory")
            JedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public StringRedisTemplate cacheRedisTemplate(
            @Qualifier("cacheRedisConnectionFactory")
            JedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private JedisConnectionFactory connectionFactory(
            TicketRedisProperties.Group group,
            long commandTimeoutMs,
            String clientName) {
        RedisConfiguration configuration = redisConfiguration(group);
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(64);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);

        JedisConnectionFactory factory;
        if (configuration instanceof RedisSentinelConfiguration sentinel) {
            factory = new JedisConnectionFactory(sentinel, poolConfig);
        } else {
            factory = new JedisConnectionFactory(
                    (RedisStandaloneConfiguration) configuration);
            factory.setPoolConfig(poolConfig);
        }
        factory.setClientName(clientName);
        factory.setUsePool(true);
        factory.setTimeout((int) Math.max(200, commandTimeoutMs));
        return factory;
    }

    private RedisConfiguration redisConfiguration(
            TicketRedisProperties.Group group) {
        if (!group.isSentinelMode()) {
            RedisStandaloneConfiguration standalone =
                    new RedisStandaloneConfiguration(
                            group.getHost(), group.getPort());
            applyPassword(standalone, group.getPassword());
            return standalone;
        }

        if (group.getSentinelNodes().isEmpty()) {
            throw new IllegalArgumentException(
                    "Sentinel 模式必须配置 Sentinel 节点");
        }
        RedisSentinelConfiguration sentinel =
                new RedisSentinelConfiguration();
        sentinel.master(group.getMaster());
        Set<RedisNode> nodes = new LinkedHashSet<>();
        for (String address : group.getSentinelNodes()) {
            nodes.add(RedisNode.fromString(address.trim()));
        }
        sentinel.setSentinels(nodes);
        applyPassword(sentinel, group.getPassword());
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
