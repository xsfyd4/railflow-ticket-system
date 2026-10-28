package com.ticket.module.ticket;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;

/**
 * 在数据库事务提交后失效缓存。
 *
 * <p>如果在事务提交前递增版本，并发的查票请求可能先读取新版本，
 * 再从尚未提交的数据库拿到旧数据并重新写入缓存。
 */
@Component
public class TicketCacheInvalidator {

    private final TicketSearchCache cache;
    private final TrainRouteIndex routeIndex;

    public TicketCacheInvalidator(TicketSearchCache cache,
                                  TrainRouteIndex routeIndex) {
        this.cache = cache;
        this.routeIndex = routeIndex;
    }

    public void invalidateDateAfterCommit(LocalDate travelDate) {
        runAfterCommit(() -> cache.invalidateDate(travelDate));
    }

    public void invalidateAllAfterCommit() {
        runAfterCommit(cache::invalidateAll);
    }

    /**
     * 车次、停站、分段、票价或站名变化后使用。
     * 先让索引进入 REBUILDING，再使 Redis 结果对象版本失效。
     */
    public void invalidateStaticDataAfterCommit() {
        runAfterCommit(() -> {
            routeIndex.beginRebuild("admin-static-data-change");
            cache.invalidateAll();
            routeIndex.startRebuild();
        });
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
}
