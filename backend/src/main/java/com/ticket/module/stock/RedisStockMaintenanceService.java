package com.ticket.module.stock;

import com.ticket.module.order.mapper.OrderRequestMapper;
import com.ticket.module.stock.entity.TicketStock;
import com.ticket.module.stock.mapper.TicketStockMapper;
import com.ticket.module.ticket.TicketSearchCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 分段库存的预热和对账。
 *
 * <p>预热只补不存在的 Key，绝不覆盖正在运行的预扣。
 * 对账先拿“日期 + 车次”短租约；预扣 Lua 看到租约时会暂缓，
 * 同时检查 order_request 是否仍有活跃请求。确认安全后才用 MySQL 修正 Redis。
 */
@Service
public class RedisStockMaintenanceService {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.StockMaintenance");

    private final TicketStockMapper stockMapper;
    private final OrderRequestMapper requestMapper;
    private final TicketStockCache stockCache;
    private final TicketSearchCache searchCache;
    private final Counter warmed;
    private final Counter differences;
    private final Counter repaired;
    private final Counter skipped;
    private final AtomicLong warmupCursor = new AtomicLong();
    private final AtomicLong reconcileCursor = new AtomicLong();

    public RedisStockMaintenanceService(
            TicketStockMapper stockMapper,
            OrderRequestMapper requestMapper,
            TicketStockCache stockCache,
            TicketSearchCache searchCache,
            MeterRegistry meterRegistry,
            @Value("${ticket.redis.maintenance.batch-size:500}")
            int batchSize,
            @Value("${ticket.redis.maintenance.max-rows-per-run:5000}")
            int maxRowsPerRun) {
        this.stockMapper = stockMapper;
        this.requestMapper = requestMapper;
        this.stockCache = stockCache;
        this.searchCache = searchCache;
        this.batchSize = Math.max(1, batchSize);
        this.maxRowsPerRun = Math.max(this.batchSize, maxRowsPerRun);
        this.warmed = meterRegistry.counter("ticket.stock.redis.warmed");
        this.differences = meterRegistry.counter(
                "ticket.stock.redis.reconcile.differences");
        this.repaired = meterRegistry.counter(
                "ticket.stock.redis.reconcile.repaired");
        this.skipped = meterRegistry.counter(
                "ticket.stock.redis.reconcile.skipped");
    }

    private final int batchSize;
    private final int maxRowsPerRun;

    public WarmupResult warmup(LocalDate startDate, int days) {
        LocalDate from = startDate == null ? LocalDate.now() : startDate;
        int actualDays = Math.max(1, days);
        LocalDate endExclusive = from.plusDays(actualDays);
        long lastId = warmupCursor.get();
        int scanned = 0;
        int created = 0;
        boolean reachedEnd = false;

        while (scanned < maxRowsPerRun) {
            int limit = Math.min(batchSize, maxRowsPerRun - scanned);
            List<TicketStock> rows = stockMapper.findForRedisMaintenance(
                    from, endExclusive, lastId, limit);
            if (rows.isEmpty()) {
                reachedEnd = true;
                break;
            }
            for (TicketStock row : rows) {
                if (stockCache.warmupIfAbsent(row)) {
                    created++;
                    warmed.increment();
                }
                lastId = row.getId();
                scanned++;
            }
            if (rows.size() < limit) {
                reachedEnd = true;
            }
        }
        warmupCursor.set(reachedEnd ? 0L : lastId);
        log.info("Redis 分段库存预热完成，date={}~{}，扫描={}，补建={}",
                from, endExclusive.minusDays(1), scanned, created);
        return new WarmupResult(scanned, created);
    }

    public ReconcileResult reconcile(LocalDate startDate, int days) {
        LocalDate from = startDate == null ? LocalDate.now() : startDate;
        int actualDays = Math.max(1, days);
        LocalDate endExclusive = from.plusDays(actualDays);
        long lastId = reconcileCursor.get();
        int scanned = 0;
        int differenceCount = 0;
        int repairedCount = 0;
        int skippedCount = 0;
        Set<LocalDate> repairedDates = new LinkedHashSet<>();
        boolean reachedEnd = false;

        while (scanned < maxRowsPerRun) {
            int limit = Math.min(batchSize, maxRowsPerRun - scanned);
            List<TicketStock> rows = stockMapper.findForRedisMaintenance(
                    from, endExclusive, lastId, limit);
            if (rows.isEmpty()) {
                reachedEnd = true;
                break;
            }
            for (TicketStock row : rows) {
                lastId = row.getId();
                scanned++;
                String actual = stockCache.readRemaining(row);
                String expected = String.valueOf(row.getRemainingCount());
                if (expected.equals(actual)) {
                    continue;
                }
                differenceCount++;
                differences.increment();

                String leaseOwner = UUID.randomUUID().toString();
                String lease = stockCache.acquireReconcileLease(
                        row.getTravelDate(), row.getTrainId(),
                        leaseOwner, Duration.ofSeconds(5));
                if (lease == null) {
                    skippedCount++;
                    skipped.increment();
                    continue;
                }
                try {
                    if (requestMapper.countActiveReservations(
                            row.getTravelDate(), row.getTrainId()) > 0) {
                        skippedCount++;
                        skipped.increment();
                        continue;
                    }
                    if (stockCache.writeExact(row)) {
                        repairedCount++;
                        repaired.increment();
                        repairedDates.add(row.getTravelDate());
                    }
                } finally {
                    stockCache.releaseReconcileLease(
                            row.getTravelDate(), row.getTrainId(), lease);
                }
            }
            if (rows.size() < limit) {
                reachedEnd = true;
            }
        }
        reconcileCursor.set(reachedEnd ? 0L : lastId);

        for (LocalDate date : repairedDates) {
            searchCache.invalidateDate(date);
        }
        log.info("Redis 库存对账完成，date={}~{}，扫描={}，差异={}，修复={}，跳过={}",
                from, endExclusive.minusDays(1), scanned,
                differenceCount, repairedCount, skippedCount);
        return new ReconcileResult(scanned, differenceCount,
                repairedCount, skippedCount);
    }

    public record WarmupResult(int scanned, int created) {
    }

    public record ReconcileResult(int scanned,
                                  int differences,
                                  int repaired,
                                  int skipped) {
    }
}
