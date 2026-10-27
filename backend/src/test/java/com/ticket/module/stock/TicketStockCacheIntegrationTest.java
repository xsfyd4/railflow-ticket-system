package com.ticket.module.stock;

import com.ticket.module.stock.TicketStockCache.ReserveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class TicketStockCacheIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 6, 1);
    private static final Long TRAIN_ID = 1L;
    private static final Integer SEAT_TYPE = 3;

    @Autowired
    private TicketStockCache stockCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO ticket_stock
                    (travel_date, train_id, segment_index, seat_type,
                     from_station_id, to_station_id,
                     total_count, sold_count, remaining_count)
                SELECT ?, seg.train_id, seg.segment_index, ?,
                       seg.from_station_id, seg.to_station_id,
                       10, 0, 10
                FROM train_segment seg
                WHERE seg.train_id = ?
                """, TEST_DATE, SEAT_TYPE, TRAIN_ID);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldAtomicallyReserveAllSegmentsAndRejectWithoutPartialDeduction() {
        assertEquals(ReserveResult.RESERVED,
                stockCache.tryReserve(TEST_DATE, TRAIN_ID, SEAT_TYPE,
                        1, 4, 3));
        assertEquals(List.of("7", "7", "7", "7"), redisCounts());

        // 第二笔要 8 张：即使前三段还有 7 张，也必须一段都不扣。
        assertEquals(ReserveResult.NOT_ENOUGH,
                stockCache.tryReserve(TEST_DATE, TRAIN_ID, SEAT_TYPE,
                        1, 4, 8));
        assertEquals(List.of("7", "7", "7", "7"), redisCounts(),
                "失败的多段预扣不能留下半扣状态");

        stockCache.releaseReservation(
                TEST_DATE, TRAIN_ID, SEAT_TYPE, 1, 4, 3);
        assertEquals(List.of("10", "10", "10", "10"), redisCounts());
    }

    private List<String> redisCounts() {
        return List.of(
                redis.opsForValue().get(stockCache.key(
                        TEST_DATE, TRAIN_ID, SEAT_TYPE, 1)),
                redis.opsForValue().get(stockCache.key(
                        TEST_DATE, TRAIN_ID, SEAT_TYPE, 2)),
                redis.opsForValue().get(stockCache.key(
                        TEST_DATE, TRAIN_ID, SEAT_TYPE, 3)),
                redis.opsForValue().get(stockCache.key(
                        TEST_DATE, TRAIN_ID, SEAT_TYPE, 4)));
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = ?
                """, TEST_DATE, TRAIN_ID);
        List<String> keys = new java.util.ArrayList<>();
        for (int segment = 1; segment <= 4; segment++) {
            keys.add(stockCache.key(
                    TEST_DATE, TRAIN_ID, SEAT_TYPE, segment));
        }
        redis.delete(keys);
    }
}
