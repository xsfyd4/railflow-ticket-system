package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.config.ReplicaRedisReader;
import com.ticket.config.RedisCircuitBreaker;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketVO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 查票结果缓存。
 *
 * <p>缓存键包含全局版本和日期版本。车次/停站/票价变化时递增全局版本；
 * 某天库存变化时递增日期版本。旧版本缓存无需扫描删除，会按 TTL 自然过期。
 */
@Component
public class TicketSearchCache {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.TicketSearchCache");
    private static final String GLOBAL_VERSION_KEY = "ticket:search:version:global";
    private static final String DATE_VERSION_KEY = "ticket:search:version:date:";
    private static final String REBUILD_LOCK_PREFIX = "ticket:search:rebuild-lock:";
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] "
                            + "then return redis.call('del', KEYS[1]) "
                            + "else return 0 end",
                    Long.class);
    private final StringRedisTemplate writeRedis;
    private final ReplicaRedisReader replicaReader;
    private final RedisCircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Duration emptyTtl;
    private final Duration rebuildLockTtl;
    private final int ttlJitterPercent;
    private final boolean enabled;
    private final AtomicLong localGeneration = new AtomicLong();
    private final Counter hits;
    private final Counter misses;
    private final Counter writes;
    private final Counter errors;
    private final Counter rebuildLockAcquired;
    private final Counter rebuildLockContended;

    public TicketSearchCache(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate writeRedis,
            ReplicaRedisReader replicaReader,
            RedisCircuitBreaker circuitBreaker,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${ticket.cache.enabled:true}") boolean enabled,
            @Value("${ticket.cache.search-ttl-seconds:10}") long ttlSeconds,
            @Value("${ticket.cache.empty-ttl-seconds:3}") long emptyTtlSeconds,
            @Value("${ticket.cache.search-ttl-jitter-percent:20}")
            int ttlJitterPercent,
            @Value("${ticket.cache.rebuild-lock-ttl-ms:3000}")
            long rebuildLockTtlMs) {
        this.writeRedis = writeRedis;
        this.replicaReader = replicaReader;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.emptyTtl = Duration.ofSeconds(Math.max(1, emptyTtlSeconds));
        this.ttlJitterPercent = Math.max(0, Math.min(90, ttlJitterPercent));
        this.rebuildLockTtl = Duration.ofMillis(
                Math.max(100, rebuildLockTtlMs));
        this.hits = meterRegistry.counter("ticket.cache.search.hits");
        this.misses = meterRegistry.counter("ticket.cache.search.misses");
        this.writes = meterRegistry.counter("ticket.cache.search.writes");
        this.errors = meterRegistry.counter("ticket.cache.search.errors");
        this.rebuildLockAcquired = meterRegistry.counter(
                "ticket.cache.search.rebuild-lock.acquired");
        this.rebuildLockContended = meterRegistry.counter(
                "ticket.cache.search.rebuild-lock.contended");
    }

    /** 查询前读取当前缓存版本，数据库查询和写入都使用同一个版本。 */
    public CacheToken currentToken(LocalDate travelDate) {
        if (!isAvailable()) {
            return CacheToken.disabled();
        }
        try {
            List<String> values = writeRedis.opsForValue().multiGet(
                    List.of(GLOBAL_VERSION_KEY, dateVersionKey(travelDate)));
            long global = parseVersion(values == null? null : values.get(0));
            long date = parseVersion(values == null? null : values.get(1));
            circuitBreaker.success();
            return new CacheToken(
                    true, global, date, localGeneration.get());
        } catch (Exception e) {
            errors.increment();
            circuitBreaker.failure(e);
            log.warn("读取查票缓存版本失败，将降级查询 MySQL：{}", e.getMessage());
            return CacheToken.disabled();
        }
    }

    public PageResult<TicketVO> get(TicketSearchDTO query, CacheToken token) {
        if (!token.enabled() || !isAvailable()) {
            return null;
        }
        String key = cacheKey(query, token);
        try {
            String json = getFromReplicaThenMaster(key);
            if (json == null) {
                misses.increment();
                return null;
            }
            TicketSearchSnapshot snapshot =
                    objectMapper.readValue(json, TicketSearchSnapshot.class);
            PageResult<TicketVO> result = snapshot.toPageResult();
            hits.increment();
            circuitBreaker.success();
            return result;
        } catch (Exception e) {
            errors.increment();
            circuitBreaker.failure(e);
            log.warn("读取查票读模型失败，将降级查询 MySQL：{}", e.getMessage());
            safeDelete(key);
            return null;
        }
    }

    public void put(TicketSearchDTO query,
                    CacheToken token,
                    PageResult<TicketVO> result) {
        if (!token.enabled() || !isAvailable()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(
                    TicketSearchSnapshot.from(result));
            writeRedis.opsForValue().set(
                    cacheKey(query, token),
                    json,
                    ttlFor(result));
            writes.increment();
            circuitBreaker.success();
        } catch (Exception e) {
            errors.increment();
            circuitBreaker.failure(e);
            log.warn("写入查票缓存失败，不影响本次查询：{}", e.getMessage());
        }
    }

    /** 某天库存变化后调用。 */
    public void invalidateDate(LocalDate travelDate) {
        localGeneration.incrementAndGet();
        if (!isAvailable()) {
            return;
        }
        try {
            String key = dateVersionKey(travelDate);
            writeRedis.opsForValue().increment(key);
            writeRedis.expire(key, Duration.ofDays(60));
            circuitBreaker.success();
            log.info("查票缓存已失效，travelDate={}", travelDate);
        } catch (Exception e) {
            errors.increment();
            circuitBreaker.failure(e);
            log.warn("递增日期缓存版本失败，date={}：{}", travelDate, e.getMessage());
        }
    }

    /** 车次、停站、票价或车站基础数据发生变化时调用。 */
    public void invalidateAll() {
        localGeneration.incrementAndGet();
        if (!isAvailable()) {
            return;
        }
        try {
            writeRedis.opsForValue().increment(GLOBAL_VERSION_KEY);
            circuitBreaker.success();
            log.info("查票缓存全局版本已递增");
        } catch (Exception e) {
            errors.increment();
            circuitBreaker.failure(e);
            log.warn("递增全局缓存版本失败：{}", e.getMessage());
        }
    }

    /**
     * 分布式重建锁。只有拿到锁的实例负责回源并写缓存，其他实例
     * 使用指数退避等待缓存，避免同一热点同时击穿数据库。
     */
    public LockToken tryAcquireRebuildLock(String cacheKey) {
        if (!isAvailable()) {
            return LockToken.unavailable(cacheKey);
        }
        String owner = UUID.randomUUID().toString();
        try {
            Boolean acquired = writeRedis.opsForValue().setIfAbsent(
                    rebuildLockKey(cacheKey), owner, rebuildLockTtl);
            circuitBreaker.success();
            if (Boolean.TRUE.equals(acquired)) {
                rebuildLockAcquired.increment();
                return LockToken.acquired(cacheKey, owner);
            }
            rebuildLockContended.increment();
            return LockToken.contended(cacheKey);
        } catch (Exception e) {
            errors.increment();
            circuitBreaker.failure(e);
            log.warn("获取查票缓存重建锁失败：{}", e.getMessage());
            return LockToken.unavailable(cacheKey);
        }
    }

    public void releaseRebuildLock(LockToken token) {
        if (token == null || !token.acquired()) {
            return;
        }
        try {
            writeRedis.execute(
                    RELEASE_LOCK_SCRIPT,
                    List.of(rebuildLockKey(token.cacheKey())),
                    token.owner());
            circuitBreaker.success();
        } catch (Exception e) {
            errors.increment();
            circuitBreaker.failure(e);
            // 不直接 DEL，避免锁过期后误删其他实例刚拿到的锁。
            log.warn("释放查票缓存重建锁失败，将由 TTL 自动释放：{}",
                    e.getMessage());
        }
    }

    public String cacheKey(TicketSearchDTO query, CacheToken token) {
        String seat = query.getSeatType() == null
                ? "all" : String.valueOf(query.getSeatType());
        return "ticket:search:v" + token.globalVersion()
                + ":" + token.dateVersion()
                + ":l" + token.localGeneration()
                + ":" + query.getTravelDate()
                + ":" + query.getFromStationId()
                + ":" + query.getToStationId()
                + ":" + seat
                + ":" + query.getPage()
                + ":" + query.getSize();
    }

    private Duration ttlFor(PageResult<TicketVO> result) {
        Duration base = result != null && result.getTotal() == 0
                ? emptyTtl : ttl;
        if (ttlJitterPercent == 0) {
            return base;
        }
        long baseMillis = base.toMillis();
        long min = Math.max(1,
                baseMillis * (100 - ttlJitterPercent) / 100);
        long max = Math.max(min,
                baseMillis * (100 + ttlJitterPercent) / 100);
        return Duration.ofMillis(ThreadLocalRandom.current()
                .nextLong(min, max + 1));
    }

    private String dateVersionKey(LocalDate travelDate) {
        return DATE_VERSION_KEY + travelDate;
    }

    private String rebuildLockKey(String cacheKey) {
        return REBUILD_LOCK_PREFIX + cacheKey;
    }

    private long parseVersion(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void safeDelete(String key) {
        try {
            writeRedis.delete(key);
        } catch (Exception ignored) {
            // 缓存清理失败不能影响数据库回源。
        }
    }

    public boolean isAvailable() {
        return enabled && circuitBreaker.isAvailable();
    }

    /**
     * 正常从两个副本中选一个读取；副本连接失败时再读主节点。
     * 新版本 Key 尚未复制时返回 null，业务会回源 MySQL 并把结果写回主节点。
     */
    private String getFromReplicaThenMaster(String key) {
        try {
            String value = replicaReader.get(key);
            if (value != null) {
                return value;
            }
        } catch (Exception replicaError) {
            log.warn("读取查票副本失败，尝试主节点：{}",
                    replicaError.getMessage());
        }
        return writeRedis.opsForValue().get(key);
    }

    public record CacheToken(boolean enabled,
                             long globalVersion,
                             long dateVersion,
                             long localGeneration) {
        public static CacheToken disabled() {
            return new CacheToken(false, 0, 0, 0);
        }
    }

    public record LockToken(boolean available,
                            boolean acquired,
                            String cacheKey,
                            String owner) {
        public static LockToken unavailable(String cacheKey) {
            return new LockToken(false, false, cacheKey, null);
        }

        public static LockToken contended(String cacheKey) {
            return new LockToken(true, false, cacheKey, null);
        }

        public static LockToken acquired(String cacheKey, String owner) {
            return new LockToken(true, true, cacheKey, owner);
        }
    }
}
