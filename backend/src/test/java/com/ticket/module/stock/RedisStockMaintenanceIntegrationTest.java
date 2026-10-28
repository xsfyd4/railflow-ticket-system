package com.ticket.module.stock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RedisStockMaintenanceIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 9, 1);
    private static final Long TRAIN_ID = 1L;
    private static final Integer SEAT_TYPE = 3;
    private static final int SEGMENT_INDEX = 4;
    private static final String ACTIVE_REQUEST = "__stock_reconcile_active__";

    @Autowired
    private RedisStockMaintenanceService maintenanceService;

    @Autowired
    private TicketStockCache stockCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("writeRedisTemplate")
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
                       30, 0, 12
                FROM train_segment seg
                WHERE seg.train_id = ?
                  AND seg.segment_index = ?
                """, TEST_DATE, SEAT_TYPE, TRAIN_ID, SEGMENT_INDEX);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldWarmMissingStockAndReconcileDifference() {
        RedisStockMaintenanceService.WarmupResult warmup =
                maintenanceService.warmup(TEST_DATE, 1);
        assertEquals(1, warmup.created());
        assertEquals("12", redisValue());

        redis.opsForValue().set(stockKey(), "1");
        RedisStockMaintenanceService.ReconcileResult reconcile =
                maintenanceService.reconcile(TEST_DATE, 1);
        assertEquals(1, reconcile.differences());
        assertEquals(1, reconcile.repaired());
        assertEquals("12", redisValue());
    }

    @Test
    void shouldNotOverwriteRedisWhileRequestIsStillActive() {
        redis.opsForValue().set(stockKey(), "1");
        jdbcTemplate.update("""
                INSERT INTO order_request
                    (request_id, user_id, payload_json, train_id, travel_date,
                     seat_type, from_segment_index, to_segment_index,
                     passenger_count, status, stock_reserved)
                VALUES (?, 1, '{}', ?, ?, ?, ?, ?, 1, 0, 0)
                """, ACTIVE_REQUEST, TRAIN_ID, TEST_DATE,
                SEAT_TYPE, SEGMENT_INDEX, SEGMENT_INDEX);

        RedisStockMaintenanceService.ReconcileResult reconcile =
                maintenanceService.reconcile(TEST_DATE, 1);
        assertEquals(1, reconcile.skipped());
        assertEquals("1", redisValue());
    }

    private String stockKey() {
        return stockCache.key(
                TEST_DATE, TRAIN_ID, SEAT_TYPE, SEGMENT_INDEX);
    }

    private String redisValue() {
        return redis.opsForValue().get(stockKey());
    }

    private void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM order_request WHERE request_id = ?",
                ACTIVE_REQUEST);
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = ?
                """, TEST_DATE, TRAIN_ID);
        redis.delete(stockKey());
    }
}
