package com.ticket.module.stock;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.stock.entity.TicketStock;
import com.ticket.module.stock.mapper.TicketStockMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 分段数字库存。
 *
 * <p>MySQL 的 ticket_stock 是最终事实来源；Redis 是异步下单前的快速准入层。
 * 一次购买覆盖多个分段时，Lua 会先检查所有分段，全部够票才统一扣减，
 * 不会出现“前两段扣了、第三段才发现没票”的半成功状态。
 */
@Component
public class TicketStockCache {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.TicketStockCache");

    /**
     * 同一个车次同一日期使用相同的 Hash Tag，Redis Cluster 下这些 Key
     * 会落在同一个 slot，Lua 才能一次原子操作多段库存。
     */
    private static final String KEY_PREFIX = "ticket:stock:";

    /**
     * 所有 Key 都存在且都够票才扣减。
     * 返回 1=扣减成功，0=某段余票不足，-1=某段数字库存尚未初始化。
     */
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            new DefaultRedisScript<>("""
                    local count = tonumber(ARGV[1])
                    for i = 1, #KEYS do
                        local remain = tonumber(redis.call('GET', KEYS[i]))
                        if not remain or remain < count then
                            return 0
                        end
                    end
                    for i = 1, #KEYS do
                        redis.call('DECRBY', KEYS[i], count)
                    end
                    return 1
                    """, Long.class);

    /**
     * 订单取消、退票、超时或消费失败时回补。
     * 任意 Key 缺失就返回 -1，由 Java 从 MySQL 重新同步，避免凭空补票。
     */
    private static final DefaultRedisScript<Long> ADJUST_SCRIPT =
            new DefaultRedisScript<>("""
                    local delta = tonumber(ARGV[1])
                    for i = 1, #KEYS do
                        if redis.call('EXISTS', KEYS[i]) == 0 then
                            return -1
                        end
                    end
                    for i = 1, #KEYS do
                        redis.call('INCRBY', KEYS[i], delta)
                    end
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final TicketStockMapper stockMapper;
    private final boolean enabled;
    private final Duration ttl;
    private final Counter reserved;
    private final Counter rejected;
    private final Counter unavailable;

    public TicketStockCache(
            StringRedisTemplate redis,
            TicketStockMapper stockMapper,
            MeterRegistry meterRegistry,
            @Value("${ticket.cache.stock-enabled:true}") boolean enabled,
            @Value("${ticket.cache.stock-ttl-days-after-travel:2}")
            long ttlDaysAfterTravel) {
        this.redis = redis;
        this.stockMapper = stockMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofDays(Math.max(1, ttlDaysAfterTravel));
        this.reserved = meterRegistry.counter("ticket.stock.redis.reserved");
        this.rejected = meterRegistry.counter("ticket.stock.redis.rejected");
        this.unavailable = meterRegistry.counter("ticket.stock.redis.unavailable");
    }

    /**
     * 原子预扣一个连续区间。
     *
     * @return RESERVED=成功；NOT_ENOUGH=至少一段不够；UNAVAILABLE=Redis 不可用/未启用
     */
    public ReserveResult tryReserve(LocalDate travelDate,
                                    Long trainId,
                                    Integer seatType,
                                    int fromSegmentIndex,
                                    int toSegmentIndex,
                                    int count) {
        if (!enabled) {
            unavailable.increment();
            return ReserveResult.UNAVAILABLE;
        }

        List<TicketStock> rows = requireRows(
                travelDate, trainId, seatType,
                fromSegmentIndex, toSegmentIndex);
        List<String> keys = rows.stream()
                .map(row -> key(travelDate, trainId, seatType,
                        row.getSegmentIndex()))
                .toList();

        try {
            /*
             * 数字库存第一次使用时从 MySQL 当前余票初始化。
             * NX 保证多台应用同时初始化时只有第一份值生效；
             * 真正的扣减仍由后面的 Lua 串行执行。
             */
            for (TicketStock row : rows) {
                redis.opsForValue().setIfAbsent(
                        key(travelDate, trainId, seatType,
                                row.getSegmentIndex()),
                        String.valueOf(row.getRemainingCount()),
                        stockTtl(travelDate));
            }

            Long result = redis.execute(
                    RESERVE_SCRIPT, keys, String.valueOf(count));
            if (result != null && result == 1L) {
                reserved.increment();
                return ReserveResult.RESERVED;
            }
            rejected.increment();
            return ReserveResult.NOT_ENOUGH;
        } catch (Exception e) {
            unavailable.increment();
            log.error("Redis 分段库存预扣失败，date={}，trainId={}，seat={}，segments={}-{}",
                    travelDate, trainId, seatType,
                    fromSegmentIndex, toSegmentIndex, e);
            return ReserveResult.UNAVAILABLE;
        }
    }

    /** 普通 HTTP 下单在 MySQL 提交后把 Redis 数字减掉。 */
    public void decrementAfterCommit(LocalDate travelDate,
                                     Long trainId,
                                     Integer seatType,
                                     int fromSegmentIndex,
                                     int toSegmentIndex,
                                     int count) {
        runAfterCommit(() -> adjustOrRefresh(
                travelDate, trainId, seatType,
                fromSegmentIndex, toSegmentIndex, -count));
    }

    /** 取消、退票、超时或异步消费失败后回补。 */
    public void incrementAfterCommit(LocalDate travelDate,
                                     Long trainId,
                                     Integer seatType,
                                     int fromSegmentIndex,
                                     int toSegmentIndex,
                                     int count) {
        runAfterCommit(() -> adjustOrRefresh(
                travelDate, trainId, seatType,
                fromSegmentIndex, toSegmentIndex, count));
    }

    /** 管理员调整库存后，以 MySQL 值整体刷新该区间。 */
    public void refreshAfterCommit(LocalDate travelDate,
                                   Long trainId,
                                   Integer seatType,
                                   int fromSegmentIndex,
                                   int toSegmentIndex) {
        runAfterCommit(() -> refreshRange(
                travelDate, trainId, seatType,
                fromSegmentIndex, toSegmentIndex));
    }

    /** 清空库存后，删除该车次在所有日期、席别、分段上的数字库存 Key。 */
    public void evictTrainAfterCommit(Long trainId) {
        runAfterCommit(() -> evictTrainKeys(trainId));
    }

    /** 异步消费失败后直接回补一整个预扣区间。 */
    public void releaseReservation(LocalDate travelDate,
                                   Long trainId,
                                   Integer seatType,
                                   int fromSegmentIndex,
                                   int toSegmentIndex,
                                   int count) {
        adjustOrRefresh(travelDate, trainId, seatType,
                fromSegmentIndex, toSegmentIndex, count);
    }

    /** 供对账工具和管理员维护使用：以 MySQL 当前余票覆盖 Redis。 */
    public void refreshRange(LocalDate travelDate,
                             Long trainId,
                             Integer seatType,
                             int fromSegmentIndex,
                             int toSegmentIndex) {
        if (!enabled) {
            return;
        }
        try {
            List<TicketStock> rows = requireRows(
                    travelDate, trainId, seatType,
                    fromSegmentIndex, toSegmentIndex);
            for (TicketStock row : rows) {
                redis.opsForValue().set(
                        key(travelDate, trainId, seatType,
                                row.getSegmentIndex()),
                        String.valueOf(row.getRemainingCount()),
                        stockTtl(travelDate));
            }
            log.info("Redis 分段库存已从 MySQL 对账，date={}，trainId={}，seat={}，segments={}-{}",
                    travelDate, trainId, seatType,
                    fromSegmentIndex, toSegmentIndex);
        } catch (Exception e) {
            unavailable.increment();
            log.error("Redis 分段库存对账失败，date={}，trainId={}，seat={}",
                    travelDate, trainId, seatType, e);
        }
    }

    /** 测试和运维排查用，生产代码不要依赖 Key 的具体字符串。 */
    public String key(LocalDate travelDate,
                      Long trainId,
                      Integer seatType,
                      int segmentIndex) {
        return KEY_PREFIX
                + "{" + travelDate + ":" + trainId + "}"
                + ":" + seatType + ":" + segmentIndex;
    }

    private void adjustOrRefresh(LocalDate travelDate,
                                 Long trainId,
                                 Integer seatType,
                                 int fromSegmentIndex,
                                 int toSegmentIndex,
                                 int delta) {
        if (!enabled) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            for (int segment = fromSegmentIndex;
                 segment <= toSegmentIndex;
                 segment++) {
                keys.add(key(travelDate, trainId, seatType, segment));
            }
            Long result = redis.execute(
                    ADJUST_SCRIPT, keys, String.valueOf(delta));
            if (result != null && result == 1L) {
                return;
            }
            /*
             * Key 到期或 Redis 被清理过时，直接按 MySQL 当前值重建。
             * MySQL 在这个时刻已经提交，因此它仍是权威值。
             */
            refreshRange(travelDate, trainId, seatType,
                    fromSegmentIndex, toSegmentIndex);
        } catch (Exception e) {
            unavailable.increment();
            log.error("Redis 分段库存增量调整失败，date={}，trainId={}，seat={}，delta={}",
                    travelDate, trainId, seatType, delta, e);
        }
    }

    private void evictTrainKeys(Long trainId) {
        if (!enabled) {
            return;
        }
        String pattern = KEY_PREFIX + "{*:" + trainId + "}:*";
        List<String> batch = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(pattern)
                .count(500)
                .build())) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 500) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redis.delete(batch);
            }
            log.info("已清理车次 Redis 数字库存，trainId={}，pattern={}",
                    trainId, pattern);
        } catch (Exception e) {
            unavailable.increment();
            log.error("清理车次 Redis 数字库存失败，trainId={}",
                    trainId, e);
        }
    }

    private List<TicketStock> requireRows(LocalDate travelDate,
                                          Long trainId,
                                          Integer seatType,
                                          int fromSegmentIndex,
                                          int toSegmentIndex) {
        List<TicketStock> rows = stockMapper.findRowsForRange(
                travelDate, trainId, seatType,
                fromSegmentIndex, toSegmentIndex);
        int expected = toSegmentIndex - fromSegmentIndex + 1;
        if (rows.size() != expected) {
            throw new BizException(ResultCode.ORDER_PRICE_CONFIG_ERROR);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).getSegmentIndex()
                    .equals(fromSegmentIndex + i)) {
                throw new BizException(ResultCode.ORDER_PRICE_CONFIG_ERROR);
            }
        }
        return rows;
    }

    private Duration stockTtl(LocalDate travelDate) {
        LocalDateTime expireAt = travelDate.plusDays(1)
                .atTime(23, 59, 59);
        Duration configured = Duration.between(LocalDateTime.now(), expireAt);
        return configured.compareTo(ttl) > 0 ? configured : ttl;
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
            return;
        }
        action.run();
    }

    public enum ReserveResult {
        RESERVED,
        NOT_ENOUGH,
        UNAVAILABLE
    }
}
