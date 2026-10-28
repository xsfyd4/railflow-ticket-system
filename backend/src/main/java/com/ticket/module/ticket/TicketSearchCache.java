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
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
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
    private final StringRedisTemplate writeRedis;
    private final ReplicaRedisReader replicaReader;
    private final RedisCircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final boolean enabled;
    private final AtomicLong localGeneration = new AtomicLong();
    private final Counter hits;
    private final Counter misses;
    private final Counter writes;
    private final Counter errors;

    public TicketSearchCache(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate writeRedis,
            ReplicaRedisReader replicaReader,
            RedisCircuitBreaker circuitBreaker,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${ticket.cache.enabled:true}") boolean enabled,
            @Value("${ticket.cache.search-ttl-seconds:10}") long ttlSeconds) {
        this.writeRedis = writeRedis;
        this.replicaReader = replicaReader;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.hits = meterRegistry.counter("ticket.cache.search.hits");
        this.misses = meterRegistry.counter("ticket.cache.search.misses");
        this.writes = meterRegistry.counter("ticket.cache.search.writes");
        this.errors = meterRegistry.counter("ticket.cache.search.errors");
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
        String key = key(query, token);
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
                    key(query, token),
                    json,
                    ttl);
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

    private String key(TicketSearchDTO query, CacheToken token) {
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

    private String dateVersionKey(LocalDate travelDate) {
        return DATE_VERSION_KEY + travelDate;
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
}
