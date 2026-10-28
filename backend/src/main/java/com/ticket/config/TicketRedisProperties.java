package com.ticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 部署配置。
 *
 * <p>cache 与 stock 是两个独立高可用数据组，拥有各自的 Master、
 * Replica、Sentinel、连接池和持久化策略。代码层面共享配置结构，
 * 运行层面不共享 CPU、内存和故障域。
 */
@ConfigurationProperties(prefix = "ticket.redis")
public class TicketRedisProperties {

    private Group cache = new Group(6380, "cachemaster");
    private Group stock = new Group(6390, "stockmaster");
    private long commandTimeoutMs = 3000;
    private long idempotencyProcessingTtlSeconds = 600;
    private long idempotencySuccessTtlSeconds = 604800;
    private long idempotencyFailedTtlSeconds = 86400;

    public Group getCache() {
        return cache;
    }

    public void setCache(Group cache) {
        this.cache = cache;
    }

    public Group getStock() {
        return stock;
    }

    public void setStock(Group stock) {
        this.stock = stock;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public long getIdempotencyProcessingTtlSeconds() {
        return idempotencyProcessingTtlSeconds;
    }

    public void setIdempotencyProcessingTtlSeconds(
            long idempotencyProcessingTtlSeconds) {
        this.idempotencyProcessingTtlSeconds =
                idempotencyProcessingTtlSeconds;
    }

    public long getIdempotencySuccessTtlSeconds() {
        return idempotencySuccessTtlSeconds;
    }

    public void setIdempotencySuccessTtlSeconds(
            long idempotencySuccessTtlSeconds) {
        this.idempotencySuccessTtlSeconds = idempotencySuccessTtlSeconds;
    }

    public long getIdempotencyFailedTtlSeconds() {
        return idempotencyFailedTtlSeconds;
    }

    public void setIdempotencyFailedTtlSeconds(
            long idempotencyFailedTtlSeconds) {
        this.idempotencyFailedTtlSeconds = idempotencyFailedTtlSeconds;
    }

    public static class Group {

        private String mode = "standalone";
        private String host = "localhost";
        private int port;
        private String master;
        private List<String> sentinelNodes = new ArrayList<>();
        private List<String> replicaNodes = new ArrayList<>();
        private String password;

        public Group() {
        }

        public Group(int port, String master) {
            this.port = port;
            this.master = master;
        }

        public boolean isSentinelMode() {
            return "sentinel".equalsIgnoreCase(mode);
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getMaster() {
            return master;
        }

        public void setMaster(String master) {
            this.master = master;
        }

        public List<String> getSentinelNodes() {
            return sentinelNodes;
        }

        public void setSentinelNodes(List<String> sentinelNodes) {
            this.sentinelNodes = sentinelNodes;
        }

        public List<String> getReplicaNodes() {
            return replicaNodes;
        }

        public void setReplicaNodes(List<String> replicaNodes) {
            this.replicaNodes = replicaNodes;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
