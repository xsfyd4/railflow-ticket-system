package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.stock.StockService;
import com.ticket.module.stock.TicketStockCache;
import com.ticket.module.stock.dto.StockAdjustDTO;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TicketSearchCacheIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 5, 1);

    @Autowired
    private TicketService ticketService;

    @Autowired
    private StockService stockService;

    @Autowired
    @Qualifier("cacheRedisTemplate")
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TicketStockCache stockCache;

    @Autowired
    private TicketSearchCache searchCache;

    @BeforeEach
    void setUp() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO ticket_stock
                    (travel_date, train_id, segment_index, seat_type,
                     from_station_id, to_station_id,
                     total_count, sold_count, remaining_count)
                SELECT ?, seg.train_id, seg.segment_index, 3,
                       seg.from_station_id, seg.to_station_id,
                       10, 0, 10
                FROM train_segment seg
                WHERE seg.train_id = 1
                """, TEST_DATE);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldUseCacheAndInvalidateAfterStockAdjustmentCommits() {
        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(TEST_DATE);
        query.setSeatType(3);

        PageResult<TicketVO> first = ticketService.search(query);
        assertEquals(10, remain(first));

        // 绕过业务写路径直接改数据库，缓存仍应返回第一次的结果。
        jdbcTemplate.update("""
                UPDATE ticket_stock
                SET remaining_count = 6
                WHERE travel_date = ? AND train_id = 1 AND seat_type = 3
                """, TEST_DATE);
        assertEquals(10, remain(ticketService.search(query)),
                "相同版本应命中 Redis，不重新查询 MySQL");

        Long stockId = jdbcTemplate.queryForObject("""
                SELECT id FROM ticket_stock
                WHERE travel_date = ? AND train_id = 1
                  AND segment_index = 1 AND seat_type = 3
                """, Long.class, TEST_DATE);
        assertNotNull(stockId);

        StockAdjustDTO adjust = new StockAdjustDTO();
        adjust.setRemainingCount(5);
        stockService.adjust(stockId, adjust);

        assertEquals(5, remain(ticketService.search(query)),
                "库存事务提交后应递增日期版本并回源 MySQL");
    }

    @Test
    void shouldJitterTtlAndUseShorterTtlForEmptyResult() {
        TicketSearchDTO hitQuery = query(TEST_DATE);
        ticketService.search(hitQuery);
        TicketSearchCache.CacheToken hitToken =
                searchCache.currentToken(TEST_DATE);
        String hitKey = searchCache.cacheKey(hitQuery, hitToken);
        Long hitTtl = redis.getExpire(hitKey, TimeUnit.MILLISECONDS);

        assertNotNull(hitTtl);
        assertTrue(hitTtl >= 7_500 && hitTtl <= 12_500,
                "10 秒基础 TTL 应在 ±20% 范围内抖动，实际=" + hitTtl);

        TicketSearchDTO emptyQuery = query(TEST_DATE.plusDays(1));
        ticketService.search(emptyQuery);
        TicketSearchCache.CacheToken emptyToken =
                searchCache.currentToken(emptyQuery.getTravelDate());
        String emptyKey = searchCache.cacheKey(emptyQuery, emptyToken);
        Long emptyTtl = redis.getExpire(emptyKey, TimeUnit.MILLISECONDS);

        assertNotNull(emptyTtl);
        assertTrue(emptyTtl >= 2_000 && emptyTtl <= 4_000,
                "空结果应使用约 3 秒 TTL，实际=" + emptyTtl);
    }

    @Test
    void shouldAllowOnlyOneOwnerToAcquireRebuildLock() {
        String cacheKey = "lock-test-" + System.nanoTime();
        TicketSearchCache.LockToken first =
                searchCache.tryAcquireRebuildLock(cacheKey);
        TicketSearchCache.LockToken second =
                searchCache.tryAcquireRebuildLock(cacheKey);

        assertTrue(first.acquired());
        assertTrue(second.available());
        assertFalse(second.acquired());

        searchCache.releaseRebuildLock(first);
        TicketSearchCache.LockToken third =
                searchCache.tryAcquireRebuildLock(cacheKey);
        assertTrue(third.acquired());
        searchCache.releaseRebuildLock(third);
    }

    private TicketSearchDTO query(LocalDate travelDate) {
        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(travelDate);
        query.setSeatType(3);
        query.setPage(1);
        query.setSize(10);
        return query;
    }

    private int remain(PageResult<TicketVO> page) {
        TicketVO g1001 = page.getRecords().stream()
                .filter(item -> "G1001".equals(item.getTrainNo()))
                .findFirst()
                .orElseThrow();
        TicketSeatVO seat = g1001.getSeats().stream()
                .filter(item -> item.getSeatType() == 3)
                .findFirst()
                .orElseThrow();
        return seat.getRemainCount();
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = 1
                """, TEST_DATE);
        for (LocalDate date : List.of(TEST_DATE, TEST_DATE.plusDays(1))) {
            Set<String> keys = redis.keys("ticket:search:*" + date + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        List<String> stockKeys = new java.util.ArrayList<>();
        for (int segment = 1; segment <= 4; segment++) {
            stockKeys.add(stockCache.key(TEST_DATE, 1L, 3, segment));
        }
        redis.delete(stockKeys);
    }
}
