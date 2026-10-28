package com.ticket.module.order;

import com.ticket.config.TicketRedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * UUID 幂等快状态。
 *
 * <p>Redis 只负责尽早挡住重复请求，数据库唯一索引仍是最终事实。
 * 状态保存在 Hash 中，抢锁和状态切换都由 Lua 原子完成：
 * <ul>
 *   <li>没有 Key：当前请求成为 owner，进入 PROCESSING；</li>
 *   <li>相同摘要正在处理：短暂等待，不重复进入事务；</li>
 *   <li>相同摘要已成功：调用方回查数据库并重放；</li>
 *   <li>摘要、用户或领域不一致：直接冲突；</li>
 *   <li>Redis 不可用：返回 UNAVAILABLE，由 MySQL 同步链路兜底。</li>
 * </ul>
 */
@Component
public class RedisIdempotencyStore {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.Idempotency");

    private static final String ORDER_PREFIX =
            "ticket:order:idempotency:";
    private static final String PAY_PREFIX =
            "ticket:pay:idempotency:";

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>("""
                    local currentUser = redis.call('HGET', KEYS[1], 'userId')
                    if not currentUser then
                        redis.call('HSET', KEYS[1],
                            'domain', ARGV[1],
                            'userId', ARGV[2],
                            'requestHash', ARGV[3],
                            'ownerToken', ARGV[4],
                            'state', 'PROCESSING',
                            'resultNo', '',
                            'failReason', '',
                            'updatedAt', ARGV[5])
                        redis.call('PEXPIRE', KEYS[1], ARGV[6])
                        return 1
                    end

                    local state = redis.call('HGET', KEYS[1], 'state')
                    local savedHash = redis.call('HGET', KEYS[1], 'requestHash')
                    if tostring(currentUser) ~= ARGV[2]
                       or tostring(savedHash) ~= ARGV[3] then
                        -- 数据库没有结果时可回收旧终态；PROCESSING 不能覆盖。
                        if ARGV[7] ~= '1' or state == 'PROCESSING' then
                            return -1
                        end
                        redis.call('HSET', KEYS[1],
                            'domain', ARGV[1],
                            'userId', ARGV[2],
                            'requestHash', ARGV[3],
                            'ownerToken', ARGV[4],
                            'state', 'PROCESSING',
                            'resultNo', '',
                            'failReason', '',
                            'updatedAt', ARGV[5])
                        redis.call('PEXPIRE', KEYS[1], ARGV[6])
                        return 1
                    end

                    if state == 'PROCESSING' then
                        return 0
                    end
                    if state == 'SUCCESS' and ARGV[7] ~= '1' then
                        return 2
                    end

                    redis.call('HSET', KEYS[1],
                        'domain', ARGV[1],
                        'ownerToken', ARGV[4],
                        'state', 'PROCESSING',
                        'resultNo', '',
                        'failReason', '',
                        'updatedAt', ARGV[5])
                    redis.call('PEXPIRE', KEYS[1], ARGV[6])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> SUCCESS_SCRIPT =
            new DefaultRedisScript<>("""
                    local owner = redis.call('HGET', KEYS[1], 'ownerToken')
                    local state = redis.call('HGET', KEYS[1], 'state')
                    if owner ~= ARGV[1] then
                        return 0
                    end
                    if state == 'SUCCESS' then
                        return 1
                    end
                    if state ~= 'PROCESSING' then
                        return -1
                    end
                    redis.call('HSET', KEYS[1],
                        'state', 'SUCCESS',
                        'resultNo', ARGV[2],
                        'failReason', '',
                        'updatedAt', ARGV[3])
                    redis.call('PEXPIRE', KEYS[1], ARGV[4])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> FAILED_SCRIPT =
            new DefaultRedisScript<>("""
                    local owner = redis.call('HGET', KEYS[1], 'ownerToken')
                    local state = redis.call('HGET', KEYS[1], 'state')
                    if owner ~= ARGV[1] then
                        return 0
                    end
                    if state ~= 'PROCESSING' then
                        return -1
                    end
                    redis.call('HSET', KEYS[1],
                        'state', 'FAILED',
                        'failReason', ARGV[2],
                        'updatedAt', ARGV[3])
                    redis.call('PEXPIRE', KEYS[1], ARGV[4])
                    return 1
                    """, Long.class);

    private final StringRedisTemplate writeRedis;
    private final TicketRedisProperties properties;

    public RedisIdempotencyStore(
            @Qualifier("writeRedisTemplate")
            StringRedisTemplate writeRedis,
            TicketRedisProperties properties) {
        this.writeRedis = writeRedis;
        this.properties = properties;
    }

    /**
     * 普通准入。调用方已经查过数据库，并确认当前 requestId 没有业务结果。
     */
    public AcquireResult acquire(String domain,
                                 String requestId,
                                 Long userId,
                                 String requestHash) {
        return acquireInternal(domain, requestId, userId,
                requestHash, false);
    }

    /**
     * Redis 显示 SUCCESS，但数据库没有对应结果时，允许重新抢占。
     * 这用于处理测试库重建、手工删单或 Redis 残留状态。
     */
    public AcquireResult acquireAfterDatabaseMiss(String domain,
                                                  String requestId,
                                                  Long userId,
                                                  String requestHash) {
        return acquireInternal(domain, requestId, userId,
                requestHash, true);
    }

    public String ownerToken() {
        return UUID.randomUUID().toString();
    }

    public boolean markSuccess(String domain,
                               String requestId,
                               String ownerToken,
                               String resultNo) {
        String key = key(domain, requestId);
        try {
            Long result = writeRedis.execute(
                    SUCCESS_SCRIPT,
                    List.of(key),
                    ownerToken,
                    resultNo == null ? "" : resultNo,
                    Instant.now().toString(),
                    String.valueOf(
                            properties.getIdempotencySuccessTtlSeconds() * 1000));
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("写入幂等成功状态失败，domain={}，requestId={}：{}",
                    domain, requestId, e.getMessage());
            return false;
        }
    }

    public boolean markFailed(String domain,
                              String requestId,
                              String ownerToken,
                              String reason) {
        String key = key(domain, requestId);
        try {
            Long result = writeRedis.execute(
                    FAILED_SCRIPT,
                    List.of(key),
                    ownerToken,
                    abbreviate(reason),
                    Instant.now().toString(),
                    String.valueOf(
                            properties.getIdempotencyFailedTtlSeconds() * 1000));
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("写入幂等失败状态失败，domain={}，requestId={}：{}",
                    domain, requestId, e.getMessage());
            return false;
        }
    }

    public State readState(String domain, String requestId) {
        try {
            List<Object> values = writeRedis.opsForHash().multiGet(
                    key(domain, requestId),
                    List.of("userId", "requestHash", "ownerToken",
                            "state", "resultNo", "failReason", "updatedAt"));
            if (values == null || values.isEmpty() || values.get(0) == null) {
                return null;
            }
            return new State(
                    Long.valueOf(String.valueOf(values.get(0))),
                    stringValue(values, 1),
                    stringValue(values, 2),
                    stringValue(values, 3),
                    stringValue(values, 4),
                    stringValue(values, 5),
                    stringValue(values, 6));
        } catch (Exception e) {
            return null;
        }
    }

    public String key(String domain, String requestId) {
        return ("PAY".equals(domain) ? PAY_PREFIX : ORDER_PREFIX)
                + requestId;
    }

    private AcquireResult acquireInternal(String domain,
                                          String requestId,
                                          Long userId,
                                          String requestHash,
                                          boolean reclaimSuccess) {
        String key = key(domain, requestId);
        String ownerToken = ownerToken();
        try {
            Long result = writeRedis.execute(
                    ACQUIRE_SCRIPT,
                    List.of(key),
                    domain,
                    String.valueOf(userId),
                    requestHash,
                    ownerToken,
                    Instant.now().toString(),
                    String.valueOf(
                            properties.getIdempotencyProcessingTtlSeconds()
                                    * 1000),
                    reclaimSuccess ? "1" : "0");
            if (result == null) {
                return new AcquireResult(
                        Status.UNAVAILABLE, ownerToken, null);
            }
            if (result == 1L) {
                return new AcquireResult(
                        Status.ACQUIRED, ownerToken, null);
            }
            if (result == -1L) {
                return new AcquireResult(
                        Status.CONFLICT, ownerToken,
                        readState(domain, requestId));
            }
            if (result == 2L) {
                return new AcquireResult(
                        Status.REPLAY, ownerToken,
                        readState(domain, requestId));
            }
            return waitForTerminal(domain, requestId, userId,
                    requestHash, ownerToken);
        } catch (Exception e) {
            log.warn("Redis 幂等准入不可用，使用 MySQL 兜底，domain={}，requestId={}：{}",
                    domain, requestId, e.getMessage());
            return new AcquireResult(
                    Status.UNAVAILABLE, ownerToken, null);
        }
    }

    private AcquireResult waitForTerminal(String domain,
                                          String requestId,
                                          Long userId,
                                          String requestHash,
                                          String firstOwnerToken) {
        for (int i = 0; i < 40; i++) {
            sleepQuietly(50L);
            State state = readState(domain, requestId);
            if (state == null) {
                return acquireInternal(domain, requestId,
                        userId, requestHash, true);
            }
            if (!state.userId().equals(userId)
                    || !state.requestHash().equals(requestHash)) {
                return new AcquireResult(
                        Status.CONFLICT, firstOwnerToken, state);
            }
            if ("SUCCESS".equals(state.state())) {
                return new AcquireResult(
                        Status.REPLAY, firstOwnerToken, state);
            }
            if ("FAILED".equals(state.state())) {
                return acquireInternal(domain, requestId,
                        userId, requestHash, true);
            }
        }
        return new AcquireResult(Status.PROCESSING,
                firstOwnerToken, readState(domain, requestId));
    }

    private String stringValue(List<Object> values, int index) {
        if (index >= values.size() || values.get(index) == null) {
            return null;
        }
        return String.valueOf(values.get(index));
    }

    private String abbreviate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "处理失败";
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public enum Status {
        ACQUIRED,
        REPLAY,
        PROCESSING,
        CONFLICT,
        UNAVAILABLE
    }

    public record AcquireResult(Status status,
                                String ownerToken,
                                State state) {
    }

    public record State(Long userId,
                        String requestHash,
                        String ownerToken,
                        String state,
                        String resultNo,
                        String failReason,
                        String updatedAt) {
    }
}
