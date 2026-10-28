package com.ticket.module.ticket.impl;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.TicketService;
import com.ticket.module.ticket.TrainRouteIndex;
import com.ticket.module.ticket.TicketSearchCache;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.mapper.TicketSearchMapper;
import com.ticket.module.ticket.vo.TicketSeatRow;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class TicketServiceImpl implements TicketService {

    private static final Logger log =
            LoggerFactory.getLogger(TicketServiceImpl.class);

    private final TicketSearchMapper mapper;
    private final TicketSearchCache cache;
    private final TrainRouteIndex routeIndex;
    private final IndexedTicketSearchService indexedSearch;
    private final Counter cacheHits;
    private final Counter cacheWaitHits;
    private final Counter cacheLockFallbacks;
    private final Counter routeMisses;
    private final Counter indexedSearches;
    private final Counter slowSearches;
    private final int rebuildWaitAttempts;
    private final long rebuildWaitBaseDelayMs;

    public TicketServiceImpl(TicketSearchMapper mapper,
                             TicketSearchCache cache,
                             TrainRouteIndex routeIndex,
                             IndexedTicketSearchService indexedSearch,
                             MeterRegistry meterRegistry,
                             @Value("${ticket.cache.rebuild-wait-attempts:5}")
                             int rebuildWaitAttempts,
                             @Value("${ticket.cache.rebuild-wait-base-delay-ms:20}")
                             long rebuildWaitBaseDelayMs) {
        this.mapper = mapper;
        this.cache = cache;
        this.routeIndex = routeIndex;
        this.indexedSearch = indexedSearch;
        this.cacheHits = meterRegistry.counter(
                "ticket.search.path.cache.hit");
        this.cacheWaitHits = meterRegistry.counter(
                "ticket.search.path.cache.wait-hit");
        this.cacheLockFallbacks = meterRegistry.counter(
                "ticket.search.path.cache.lock-fallback");
        this.routeMisses = meterRegistry.counter(
                "ticket.search.path.route-miss");
        this.indexedSearches = meterRegistry.counter(
                "ticket.search.path.index");
        this.slowSearches = meterRegistry.counter(
                "ticket.search.path.mysql-slow");
        this.rebuildWaitAttempts = Math.max(1,
                Math.min(10, rebuildWaitAttempts));
        this.rebuildWaitBaseDelayMs = Math.max(1,
                Math.min(1000, rebuildWaitBaseDelayMs));
    }

    @Override
    public PageResult<TicketVO> search(TicketSearchDTO q) {
        // 1. 兜底
        int page = (q.getPage() == null || q.getPage() < 1) ? 1 : q.getPage();
        int size = (q.getSize() == null || q.getSize() < 1) ? 10 : q.getSize();
        if (size > 50) {
            size = 50;
        }
        q.setPage(page);
        q.setSize(size);

        /*
         * 缓存命中时完全不访问 MySQL，也不开启数据库事务。
         * 版本在数据库查询前确定，写入时继续使用同一版本；
         * 如果期间发生库存变化，新版本会使本次旧结果不可达。
         */
        boolean indexReady = routeIndex.isReady();
        /*
         * 索引已经完整加载时不存在的站对可以直接判定为无车次。
         * 这类请求不访问 Redis，也不写空缓存，避免随机不存在的站对
         * 和日期制造海量缓存 Key 或穿透到 MySQL。
         */
        if (indexReady && !routeIndex.hasRoute(
                q.getFromStationId(), q.getToStationId())) {
            routeMisses.increment();
            return PageResult.of(0, page, size, List.of());
        }

        TicketSearchCache.CacheToken cacheToken =
                cache.currentToken(q.getTravelDate());
        if (cacheToken.enabled()) {
            PageResult<TicketVO> cached = cache.get(q, cacheToken);
            if (cached != null) {
                cacheHits.increment();
                return cached;
            }
        }

        if (!indexReady) {
            return loadCacheWithLock(
                    q, cacheToken, () -> searchMysqlSlow(q));
        }

        try {
            return loadCacheWithLock(
                    q, cacheToken, () -> {
                        PageResult<TicketVO> result =
                                indexedSearch.search(q);
                        indexedSearches.increment();
                        return result;
                    });
        } catch (Exception e) {
            log.warn("索引快查询失败，降级 MySQL 慢查询：{}", e.getMessage());
            PageResult<TicketVO> fallback = searchMysqlSlow(q);
            cache.put(q, cacheToken, fallback);
            return fallback;
        }
    }

    /**
     * 保留完整的三阶段 MySQL 查询。
     *
     * <p>Redis 熔断、缓存关闭、索引重建或索引不可用时使用。
     */
    private PageResult<TicketVO> searchMysqlSlow(TicketSearchDTO q) {
        slowSearches.increment();
        int page = q.getPage();
        int size = q.getSize();

        // 2. 先数车次
        long total = mapper.countTrains(q);
        if (total == 0) {
            PageResult<TicketVO> result =
                    PageResult.of(0, page, size, List.of());
            return result;
        }

        // 3. 取当页车次 id（分页在这一步，不会被席别放大）
        int offset = (page - 1) * size;
        List<Long> ids = mapper.findTrainIds(q, offset);
        if (ids.isEmpty()) {
            PageResult<TicketVO> result =
                    PageResult.of(total, page, size, List.of());
            return result;
        }

        // 4. 按 id 查席别余票（一行 = 一个车次 + 一个席别）
        List<TicketSeatRow> rows = mapper.findSeatRows(q, ids);

        // 5. 按车次合并席别
        Map<Long, TicketVO> byTrain = new HashMap<>();
        for (TicketSeatRow r : rows) {
            TicketVO vo = byTrain.computeIfAbsent(r.getTrainId(), id -> toVO(r));
            vo.getSeats().add(new TicketSeatVO(
                    r.getSeatType(), r.getRemainCount(), r.getPrice()));
        }

        // 6. 按 id 的原顺序输出（SQL 已经按发车时间排好）
        List<TicketVO> records = new ArrayList<>();
        for (Long id : ids) {
            TicketVO vo = byTrain.get(id);
            if (vo != null) {
                records.add(vo);
            }
        }
        PageResult<TicketVO> result =
                PageResult.of(total, page, size, records);
        return result;
    }

    private PageResult<TicketVO> loadCacheWithLock(
            TicketSearchDTO query,
            TicketSearchCache.CacheToken token,
            Supplier<PageResult<TicketVO>> loader) {
        if (!token.enabled()) {
            return loader.get();
        }

        String cacheKey = cache.cacheKey(query, token);
        TicketSearchCache.LockToken lock =
                cache.tryAcquireRebuildLock(cacheKey);
        if (lock.acquired()) {
            return refreshUnderLock(query, token, loader, lock);
        }

        if (!lock.available()) {
            cacheLockFallbacks.increment();
            return loader.get();
        }

        for (int attempt = 0; attempt < rebuildWaitAttempts; attempt++) {
            if (!sleepBackoff(attempt)) {
                break;
            }
            PageResult<TicketVO> cached = cache.get(query, token);
            if (cached != null) {
                cacheWaitHits.increment();
                return cached;
            }
            lock = cache.tryAcquireRebuildLock(cacheKey);
            if (lock.acquired()) {
                return refreshUnderLock(query, token, loader, lock);
            }
            if (!lock.available()) {
                break;
            }
        }

        cacheLockFallbacks.increment();
        return loader.get();
    }

    private PageResult<TicketVO> refreshUnderLock(
            TicketSearchDTO query,
            TicketSearchCache.CacheToken token,
            Supplier<PageResult<TicketVO>> loader,
            TicketSearchCache.LockToken lock) {
        try {
            PageResult<TicketVO> cached = cache.get(query, token);
            if (cached != null) {
                cacheWaitHits.increment();
                return cached;
            }
            PageResult<TicketVO> result = loader.get();
            cache.put(query, token, result);
            return result;
        } finally {
            cache.releaseRebuildLock(lock);
        }
    }

    private boolean sleepBackoff(int attempt) {
        long exponential = rebuildWaitBaseDelayMs
                << Math.min(attempt, 10);
        long jitter = ThreadLocalRandom.current()
                .nextLong(rebuildWaitBaseDelayMs + 1);
        try {
            Thread.sleep(Math.min(1000, exponential + jitter));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private TicketVO toVO(TicketSeatRow r) {
        TicketVO vo = new TicketVO();
        vo.setTrainId(r.getTrainId());
        vo.setTrainNo(r.getTrainNo());
        vo.setTrainType(r.getTrainType());
        vo.setFromStationName(r.getFromStationName());
        vo.setToStationName(r.getToStationName());
        vo.setDepartTime(r.getDepartTime());
        vo.setArriveTime(r.getArriveTime());
        vo.setDurationMinutes(r.getDurationMinutes());
        vo.setMileage(r.getMileage());
        return vo;
    }
}
