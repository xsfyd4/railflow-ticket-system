package com.ticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 部署和访问策略配置。
 *
 * <p>本机测试可以使用 standalone；Docker 运行环境使用 sentinel。
 * 无论哪种部署，写命令固定走主节点，查票对象读取优先走副本。
 */
@ConfigurationProperties(prefix = "ticket.redis")
public class TicketRedisProperties {

    private String mode = "standalone";
    private String host = "localhost";
    private int port = 6380;
    private String master = "mymaster";
    private List<String> sentinelNodes = new ArrayList<>();
    private List<String> replicaNodes = new ArrayList<>(
            List.of("localhost:6381", "localhost:6382"));
    private String password;
    private long commandTimeoutMs = 3000;
    private long idempotencyProcessingTtlSeconds = 600;
    private long idempotencySuccessTtlSeconds = 604800;
    private long idempotencyFailedTtlSeconds = 86400;

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
        this.idempotencySuccessTtlSeconds =
                idempotencySuccessTtlSeconds;
    }

    public long getIdempotencyFailedTtlSeconds() {
        return idempotencyFailedTtlSeconds;
    }

    public void setIdempotencyFailedTtlSeconds(
            long idempotencyFailedTtlSeconds) {
        this.idempotencyFailedTtlSeconds =
                idempotencyFailedTtlSeconds;
    }
}
