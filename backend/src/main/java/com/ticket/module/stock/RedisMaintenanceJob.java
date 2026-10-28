package com.ticket.module.stock;

import com.ticket.module.ticket.TicketSearchWarmupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 预热与对账的调度入口。
 *
 * <p>任务彼此独立：库存预热失败不影响查票预热；对账失败也不会阻断业务。
 * 默认限制每次处理的批量和最大行数，避免后台任务抢光连接池。
 */
@Component
public class RedisMaintenanceJob {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.MaintenanceJob");

    private final RedisStockMaintenanceService stockMaintenance;
    private final TicketSearchWarmupService searchWarmup;
    private final boolean warmupEnabled;
    private final boolean reconcileEnabled;
    private final int warmupDays;
    private final int reconcileDays;

    public RedisMaintenanceJob(
            RedisStockMaintenanceService stockMaintenance,
            TicketSearchWarmupService searchWarmup,
            @Value("${ticket.redis.warmup.enabled:false}")
            boolean warmupEnabled,
            @Value("${ticket.redis.reconcile.enabled:true}")
            boolean reconcileEnabled,
            @Value("${ticket.redis.warmup.stock-days:7}")
            int warmupDays,
            @Value("${ticket.redis.reconcile.days:3}")
            int reconcileDays) {
        this.stockMaintenance = stockMaintenance;
        this.searchWarmup = searchWarmup;
        this.warmupEnabled = warmupEnabled;
        this.reconcileEnabled = reconcileEnabled;
        this.warmupDays = Math.max(1, warmupDays);
        this.reconcileDays = Math.max(1, reconcileDays);
    }

    @Scheduled(
            initialDelayString =
                    "${ticket.redis.warmup.initial-delay-ms:20000}",
            fixedDelayString =
                    "${ticket.redis.warmup.interval-ms:3600000}")
    public void warmup() {
        if (!warmupEnabled) {
            return;
        }
        try {
            stockMaintenance.warmup(LocalDate.now(), warmupDays);
            searchWarmup.warmup();
        } catch (Exception e) {
            log.error("Redis 预热任务失败", e);
        }
    }

    @Scheduled(
            initialDelayString =
                    "${ticket.redis.reconcile.initial-delay-ms:60000}",
            fixedDelayString =
                    "${ticket.redis.reconcile.interval-ms:300000}")
    public void reconcile() {
        if (!reconcileEnabled) {
            return;
        }
        try {
            stockMaintenance.reconcile(LocalDate.now(), reconcileDays);
        } catch (Exception e) {
            log.error("Redis 库存对账任务失败", e);
        }
    }
}
