package com.ticket.config;

import jakarta.annotation.PreDestroy;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 查票对象专用副本读取器。
 *
 * <p>Spring Data Redis 的 Jedis Sentinel 连接池只连接 Master。
 * 为了真正分摊到两个 Replica，本类通过 Sentinel 查询副本地址，
 * 建立两个独立连接池，并按请求轮询；任意副本失败时尝试另一个副本。
 * Redis 全部不可用时由 {@code TicketSearchCache} 继续降级 MySQL。
 */
@Component
public class ReplicaRedisReader {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.ReplicaReader");
    private static final long DISCOVERY_TTL_MILLIS = 30_000L;

    private final TicketRedisProperties properties;
    private final TicketRedisProperties.Group cache;
    private final AtomicInteger nextReplica = new AtomicInteger();
    private final Map<HostAndPort, JedisPooled> pools =
            new ConcurrentHashMap<>();
    private volatile List<HostAndPort> replicas = List.of();
    private volatile long discoveredAt;

    public ReplicaRedisReader(TicketRedisProperties properties) {
        this.properties = properties;
        this.cache = properties.getCache();
    }

    public String get(String key) {
        ensureReplicas();
        List<HostAndPort> current = replicas;
        if (current.isEmpty()) {
            throw new IllegalStateException("没有可用的 Redis Replica");
        }

        RuntimeException lastError = null;
        int start = Math.floorMod(
                nextReplica.getAndIncrement(), current.size());
        for (int offset = 0; offset < current.size(); offset++) {
            HostAndPort node = current.get(
                    (start + offset) % current.size());
            try {
                String value = pool(node).get(key);
                if (value != null) {
                    return value;
                }
            } catch (RuntimeException e) {
                lastError = e;
                closePool(node);
                discoveredAt = 0L;
                log.warn("Redis 查票副本读取失败，node={}：{}",
                        node, e.getMessage());
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    /** 暴露当前副本目标，供健康检查和故障演练观察。 */
    public List<HostAndPort> replicaTargets() {
        ensureReplicas();
        return replicas;
    }

    private void ensureReplicas() {
        long now = Instant.now().toEpochMilli();
        if (!replicas.isEmpty()
                && now - discoveredAt < DISCOVERY_TTL_MILLIS) {
            return;
        }
        synchronized (this) {
            now = Instant.now().toEpochMilli();
            if (!replicas.isEmpty()
                    && now - discoveredAt < DISCOVERY_TTL_MILLIS) {
                return;
            }
            try {
                List<HostAndPort> discovered = cache.isSentinelMode()
                        ? discoverFromSentinel()
                        : parseNodes(cache.getReplicaNodes());
                if (!discovered.isEmpty()) {
                    replicas = List.copyOf(discovered);
                    discoveredAt = now;
                    log.info("Redis 查票副本列表已刷新：{}", replicas);
                }
            } catch (RuntimeException e) {
                log.warn("发现 Redis 查票副本失败，保留旧列表：{}",
                        e.getMessage());
            }
        }
    }

    private List<HostAndPort> discoverFromSentinel() {
        RuntimeException lastError = null;
        for (String sentinelAddress : cache.getSentinelNodes()) {
            HostAndPort sentinelNode = HostAndPort.from(
                    sentinelAddress.trim());
            try (Jedis sentinel = new Jedis(
                    sentinelNode, clientConfig("train-ticket-sentinel-reader"))) {
                List<HostAndPort> found = new ArrayList<>();
                for (Map<String, String> replica
                        : sentinel.sentinelReplicas(cache.getMaster())) {
                    String flags = replica.getOrDefault("flags", "");
                    String link = replica.getOrDefault(
                            "master-link-status", "ok");
                    if (flags.contains("s_down")
                            || flags.contains("o_down")
                            || flags.contains("disconnected")
                            || !"ok".equals(link)) {
                        continue;
                    }
                    String host = replica.getOrDefault(
                            "ip", replica.get("host"));
                    String port = replica.get("port");
                    if (host != null && port != null) {
                        found.add(new HostAndPort(
                                host, Integer.parseInt(port)));
                    }
                }
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Sentinel 没有报告可用 Replica");
    }

    private List<HostAndPort> parseNodes(List<String> nodes) {
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream()
                .filter(node -> node != null && !node.isBlank())
                .map(node -> HostAndPort.from(node.trim()))
                .toList();
    }

    private JedisPooled pool(HostAndPort node) {
        return pools.computeIfAbsent(node, key -> {
            GenericObjectPoolConfig<Connection> poolConfig =
                    new GenericObjectPoolConfig<>();
            poolConfig.setMaxTotal(32);
            poolConfig.setMaxIdle(8);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            return new JedisPooled(
                    key,
                    clientConfig("train-ticket-search-reader"),
                    poolConfig);
        });
    }

    private DefaultJedisClientConfig clientConfig(String clientName) {
        DefaultJedisClientConfig.Builder builder =
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis((int) properties
                                .getCommandTimeoutMs())
                        .socketTimeoutMillis((int) properties
                                .getCommandTimeoutMs())
                        .clientName(clientName);
        if (cache.getPassword() != null
                && !cache.getPassword().isBlank()) {
            builder.password(cache.getPassword());
        }
        return builder.build();
    }

    private void closePool(HostAndPort node) {
        JedisPooled pool = pools.remove(node);
        if (pool != null) {
            try {
                pool.close();
            } catch (RuntimeException ignored) {
                // 关闭失败不能掩盖本次读取失败。
            }
        }
    }

    @PreDestroy
    public void close() {
        pools.keySet().forEach(this::closePool);
    }
}
