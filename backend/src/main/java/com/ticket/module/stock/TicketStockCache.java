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
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.function.Consumer;

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
    private static final String RESERVATION_PREFIX = "ticket:reservation:";
    private static final String PENDING_RESERVATION_PREFIX =
            "ticket:reservation:pending:";
    private static final String RECONCILE_LOCK_PREFIX =
            "ticket:stock-reconcile:{";

    /**
     * 所有 Key 都存在且都够票才扣减。
     * 返回 1=成功，0=余票不足，-2=正在对账，-3=该请求已预扣。
     */
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            new DefaultRedisScript<>("""
                    local count = tonumber(ARGV[1])
                    if redis.call('EXISTS', KEYS[1]) == 1 then
                        return -2
                    end
                    if redis.call('EXISTS', KEYS[2]) == 1 then
                        return -3
                    end
                    for i = 4, #KEYS do
                        local remain = tonumber(redis.call('GET', KEYS[i]))
                        if not remain or remain < count then
                            return 0
                        end
                    end
                    for i = 4, #KEYS do
                        redis.call('DECRBY', KEYS[i], count)
                    end
                    redis.call('HSET', KEYS[2],
                        'requestId', ARGV[2],
                        'trainId', ARGV[3],
                        'seatType', ARGV[4],
                        'fromSegmentIndex', ARGV[5],
                        'toSegmentIndex', ARGV[6],
                        'count', count,
                        'state', 'RESERVED',
                        'reservedAt', ARGV[7])
                    redis.call('PEXPIRE', KEYS[2], ARGV[8])
                    redis.call('ZADD', KEYS[3], ARGV[7], ARGV[2])
                    redis.call('PEXPIRE', KEYS[3], ARGV[8])
                    return 1
                    """, Long.class);

    /**
     * 失败补偿：只有 reservation 仍是 RESERVED 时才回补，保证最多一次。
     */
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        return -1
                    end
                    local state = redis.call('HGET', KEYS[1], 'state')
                    if state == 'RELEASED' then
                        return 0
                    end
                    if state ~= 'RESERVED' then
                        return -3
                    end
                    if redis.call('EXISTS', KEYS[2]) == 1 then
                        return -2
                    end
                    local count = tonumber(redis.call(
                        'HGET', KEYS[1], 'count'))
                    for i = 4, #KEYS do
                        if redis.call('EXISTS', KEYS[i]) == 0 then
                            return -4
                        end
                    end
                    for i = 4, #KEYS do
                        redis.call('INCRBY', KEYS[i], count)
                    end
                    redis.call('HSET', KEYS[1],
                        'state', 'RELEASED',
                        'releasedAt', ARGV[1])
                    redis.call('PEXPIRE', KEYS[1], ARGV[2])
                    redis.call('ZREM', KEYS[3], ARGV[3])
                    return 1
                    """, Long.class);

    /** 下单成功后把 reservation 标记为 SOLD，并从待恢复索引移除。 */
    private static final DefaultRedisScript<Long> COMPLETE_RESERVATION_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        return 0
                    end
                    redis.call('HSET', KEYS[1], 'state', 'SOLD')
                    redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    redis.call('ZREM', KEYS[2], ARGV[2])
                    return 1
                    """, Long.class);

    /**
     * 订单取消、退票、超时或消费失败时回补。
     * 任意 Key 缺失就返回 -1，由 Java 从 MySQL 重新同步，避免凭空补票。
     */
    private static final DefaultRedisScript<Long> ADJUST_SCRIPT =
            new DefaultRedisScript<>("""
                    local delta = tonumber(ARGV[1])
                    if redis.call('EXISTS', KEYS[1]) == 1 then
                        return -2
                    end
                    for i = 2, #KEYS do
                        if redis.call('EXISTS', KEYS[i]) == 0 then
                            return -1
                        end
                    end
                    for i = 2, #KEYS do
                        redis.call('INCRBY', KEYS[i], delta)
                    end
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final TicketStockMapper stockMapper;
    private final boolean enabled;
    private final Duration ttl;
    private final Counter reserved;
    private final Counter rejected;
    private final Counter unavailable;

    public TicketStockCache(
            @Qualifier("writeRedisTemplate")
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
    public ReserveResult tryReserve(String requestId,
                                    LocalDate travelDate,
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
        List<String> stockKeys = rows.stream()
                .map(row -> key(travelDate, trainId, seatType,
                        row.getSegmentIndex()))
                .collect(java.util.stream.Collectors.toCollection(
                        ArrayList::new));
        List<String> keys = new ArrayList<>();
        keys.add(reconcileLockKey(travelDate, trainId));
        keys.add(reservationKey(travelDate, trainId, requestId));
        keys.add(pendingReservationKey(travelDate, trainId));
        keys.addAll(stockKeys);

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
                    RESERVE_SCRIPT, keys,
                    String.valueOf(count),
                    requestId,
                    String.valueOf(trainId),
                    String.valueOf(seatType),
                    String.valueOf(fromSegmentIndex),
                    String.valueOf(toSegmentIndex),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(stockTtl(travelDate).toMillis()));
            if (result != null && result == 1L) {
                reserved.increment();
                return ReserveResult.RESERVED;
            }
            if (result != null && result == -2L) {
                unavailable.increment();
                log.info("库存正在对账，暂缓预扣，date={}，trainId={}",
                        travelDate, trainId);
                return ReserveResult.UNAVAILABLE;
            }
            if (result != null && result == -3L) {
                return ReserveResult.ALREADY_RESERVED;
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

    public void releaseReservationAfterCommit(
            String requestId,
            LocalDate travelDate,
            Long trainId,
            Integer seatType,
            int fromSegmentIndex,
            int toSegmentIndex,
            Consumer<ReleaseResult> resultHandler) {
        runAfterCommit(() -> {
            ReleaseResult result = releaseReservationNow(
                    requestId, travelDate, trainId, seatType,
                    fromSegmentIndex, toSegmentIndex);
            if (resultHandler != null) {
                resultHandler.accept(result);
            }
        });
    }

    public void completeReservationAfterCommit(
            String requestId,
            LocalDate travelDate,
            Long trainId) {
        runAfterCommit(() -> completeReservationNow(
                requestId, travelDate, trainId));
    }

    public boolean reservationExists(String requestId,
                                     LocalDate travelDate,
                                     Long trainId) {
        if (!enabled) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(
                    reservationKey(travelDate, trainId, requestId)));
        } catch (Exception e) {
            unavailable.increment();
            return false;
        }
    }

    public ReleaseResult releaseReservationNow(
            String requestId,
            LocalDate travelDate,
            Long trainId,
            Integer seatType,
            int fromSegmentIndex,
            int toSegmentIndex) {
        if (!enabled) {
            return ReleaseResult.UNAVAILABLE;
        }
        try {
            List<String> keys = reservationKeys(
                    requestId, travelDate, trainId, seatType,
                    fromSegmentIndex, toSegmentIndex);
            Long result = redis.execute(
                    RELEASE_RESERVATION_SCRIPT,
                    keys,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(stockTtl(travelDate).toMillis()),
                    requestId);
            if (result != null && result == 1L) {
                return ReleaseResult.RELEASED;
            }
            if (result != null && result == 0L) {
                return ReleaseResult.ALREADY_RELEASED;
            }
            if (result != null && result == -1L) {
                boolean refreshed = refreshRange(travelDate, trainId, seatType,
                        fromSegmentIndex, toSegmentIndex);
                return refreshed
                        ? ReleaseResult.MARKER_MISSING
                        : ReleaseResult.UNAVAILABLE;
            }
            if (result != null && result == -2L) {
                return ReleaseResult.RECONCILING;
            }
            if (result != null && result == -4L) {
                boolean refreshed = refreshRange(travelDate, trainId, seatType,
                        fromSegmentIndex, toSegmentIndex);
                return refreshed
                        ? ReleaseResult.KEYS_MISSING
                        : ReleaseResult.UNAVAILABLE;
            }
            return ReleaseResult.MANUAL_REVIEW;
        } catch (Exception e) {
            unavailable.increment();
            log.error("释放 Redis reservation 失败，requestId={}，date={}，trainId={}",
                    requestId, travelDate, trainId, e);
            return ReleaseResult.UNAVAILABLE;
        }
    }

    public boolean completeReservationNow(
            String requestId,
            LocalDate travelDate,
            Long trainId) {
        if (!enabled) {
            return false;
        }
        try {
            Long result = redis.execute(
                    COMPLETE_RESERVATION_SCRIPT,
                    List.of(
                            reservationKey(travelDate, trainId, requestId),
                            pendingReservationKey(travelDate, trainId)),
                    String.valueOf(stockTtl(travelDate).toMillis()),
                    requestId);
            return result != null && result == 1L;
        } catch (Exception e) {
            unavailable.increment();
            log.warn("完成 Redis reservation 标记失败，requestId={}：{}",
                    requestId, e.getMessage());
            return false;
        }
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
    public boolean refreshRange(LocalDate travelDate,
                             Long trainId,
                             Integer seatType,
                             int fromSegmentIndex,
                             int toSegmentIndex) {
        if (!enabled) {
            return false;
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
            return true;
        } catch (Exception e) {
            unavailable.increment();
            log.error("Redis 分段库存对账失败，date={}，trainId={}，seat={}",
                    travelDate, trainId, seatType, e);
            return false;
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

    public String reservationKey(LocalDate travelDate,
                                 Long trainId,
                                 String requestId) {
        return RESERVATION_PREFIX
                + "{" + travelDate + ":" + trainId + "}:"
                + requestId;
    }

    public String pendingReservationKey(LocalDate travelDate,
                                        Long trainId) {
        return PENDING_RESERVATION_PREFIX
                + "{" + travelDate + ":" + trainId + "}";
    }

    public boolean warmupIfAbsent(TicketStock row) {
        if (!enabled || row == null) {
            return false;
        }
        try {
            Boolean created = redis.opsForValue().setIfAbsent(
                    key(row.getTravelDate(), row.getTrainId(),
                            row.getSeatType(), row.getSegmentIndex()),
                    String.valueOf(row.getRemainingCount()),
                    stockTtl(row.getTravelDate()));
            return Boolean.TRUE.equals(created);
        } catch (Exception e) {
            unavailable.increment();
            log.warn("预热分段库存失败，date={}，trainId={}，segment={}：{}",
                    row.getTravelDate(), row.getTrainId(),
                    row.getSegmentIndex(), e.getMessage());
            return false;
        }
    }

    public String readRemaining(TicketStock row) {
        try {
            return redis.opsForValue().get(key(
                    row.getTravelDate(),
                    row.getTrainId(),
                    row.getSeatType(),
                    row.getSegmentIndex()));
        } catch (Exception e) {
            unavailable.increment();
            return null;
        }
    }

    public boolean writeExact(TicketStock row) {
        try {
            redis.opsForValue().set(
                    key(row.getTravelDate(), row.getTrainId(),
                            row.getSeatType(), row.getSegmentIndex()),
                    String.valueOf(row.getRemainingCount()),
                    stockTtl(row.getTravelDate()));
            return true;
        } catch (Exception e) {
            unavailable.increment();
            log.warn("对账修复分段库存失败，date={}，trainId={}，segment={}：{}",
                    row.getTravelDate(), row.getTrainId(),
                    row.getSegmentIndex(), e.getMessage());
            return false;
        }
    }

    public String acquireReconcileLease(LocalDate travelDate,
                                        Long trainId,
                                        String ownerToken,
                                        Duration lease) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(
                    reconcileLockKey(travelDate, trainId),
                    ownerToken, lease);
            return Boolean.TRUE.equals(acquired) ? ownerToken : null;
        } catch (Exception e) {
            unavailable.increment();
            return null;
        }
    }

    public void releaseReconcileLease(LocalDate travelDate,
                                      Long trainId,
                                      String ownerToken) {
        if (ownerToken == null) {
            return;
        }
        try {
            redis.execute(RELEASE_LOCK_SCRIPT,
                    List.of(reconcileLockKey(travelDate, trainId)),
                    ownerToken);
        } catch (Exception e) {
            log.warn("释放库存对账租约失败，date={}，trainId={}：{}",
                    travelDate, trainId, e.getMessage());
        }
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
            keys.add(0, reconcileLockKey(travelDate, trainId));
            Long result = redis.execute(
                    ADJUST_SCRIPT, keys, String.valueOf(delta));
            if (result != null && result == 1L) {
                return;
            }
            if (result != null && result == -2L) {
                log.info("库存正在对账，跳过本次增量同步，date={}，trainId={}，delta={}",
                        travelDate, trainId, delta);
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

    private String reconcileLockKey(LocalDate travelDate, Long trainId) {
        return RECONCILE_LOCK_PREFIX + travelDate + ":" + trainId + "}";
    }

    private List<String> reservationKeys(
            String requestId,
            LocalDate travelDate,
            Long trainId,
            Integer seatType,
            int fromSegmentIndex,
            int toSegmentIndex) {
        List<String> keys = new ArrayList<>();
        keys.add(reservationKey(travelDate, trainId, requestId));
        keys.add(reconcileLockKey(travelDate, trainId));
        keys.add(pendingReservationKey(travelDate, trainId));
        for (int segment = fromSegmentIndex;
             segment <= toSegmentIndex;
             segment++) {
            keys.add(key(travelDate, trainId, seatType, segment));
        }
        return keys;
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
        ALREADY_RESERVED,
        NOT_ENOUGH,
        UNAVAILABLE
    }

    public enum ReleaseResult {
        RELEASED,
        ALREADY_RELEASED,
        MARKER_MISSING,
        KEYS_MISSING,
        RECONCILING,
        MANUAL_REVIEW,
        UNAVAILABLE
    }
}
