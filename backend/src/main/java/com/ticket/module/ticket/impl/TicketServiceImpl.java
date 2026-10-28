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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketServiceImpl implements TicketService {

    private static final Logger log =
            LoggerFactory.getLogger(TicketServiceImpl.class);

    private final TicketSearchMapper mapper;
    private final TicketSearchCache cache;
    private final TrainRouteIndex routeIndex;
    private final IndexedTicketSearchService indexedSearch;
    private final Counter cacheHits;
    private final Counter indexedSearches;
    private final Counter slowSearches;

    public TicketServiceImpl(TicketSearchMapper mapper,
                             TicketSearchCache cache,
                             TrainRouteIndex routeIndex,
                             IndexedTicketSearchService indexedSearch,
                             MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.cache = cache;
        this.routeIndex = routeIndex;
        this.indexedSearch = indexedSearch;
        this.cacheHits = meterRegistry.counter(
                "ticket.search.path.cache.hit");
        this.indexedSearches = meterRegistry.counter(
                "ticket.search.path.index");
        this.slowSearches = meterRegistry.counter(
                "ticket.search.path.mysql-slow");
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
        if (!routeIndex.isReady() || !cache.isAvailable()) {
            return searchMysqlSlow(q);
        }

        TicketSearchCache.CacheToken cacheToken =
                cache.currentToken(q.getTravelDate());
        if (!cacheToken.enabled()) {
            return searchMysqlSlow(q);
        }
        PageResult<TicketVO> cached = cache.get(q, cacheToken);
        if (cached != null) {
            cacheHits.increment();
            return cached;
        }
        if (!cache.isAvailable()) {
            return searchMysqlSlow(q);
        }

        try {
            PageResult<TicketVO> result = indexedSearch.search(q);
            indexedSearches.increment();
            cache.put(q, cacheToken, result);
            return result;
        } catch (Exception e) {
            log.warn("索引快查询失败，降级 MySQL 慢查询：{}", e.getMessage());
            return searchMysqlSlow(q);
        }
    }

    /**
     * 保留完整的三阶段 MySQL 查询。
     *
     * <p>Redis 熔断、缓存关闭、索引重建或索引不可用时使用。
     */
    private PageResult<TicketVO> searchMysqlSlow(TicketSearchDTO q) {
        slowSearches.increment();
        TicketSearchCache.CacheToken cacheToken =
                cache.currentToken(q.getTravelDate());
        int page = q.getPage();
        int size = q.getSize();

        // 2. 先数车次
        long total = mapper.countTrains(q);
        if (total == 0) {
            PageResult<TicketVO> result =
                    PageResult.of(0, page, size, List.of());
            cache.put(q, cacheToken, result);
            return result;
        }

        // 3. 取当页车次 id（分页在这一步，不会被席别放大）
        int offset = (page - 1) * size;
        List<Long> ids = mapper.findTrainIds(q, offset);
        if (ids.isEmpty()) {
            PageResult<TicketVO> result =
                    PageResult.of(total, page, size, List.of());
            cache.put(q, cacheToken, result);
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
        cache.put(q, cacheToken, result);
        return result;
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
