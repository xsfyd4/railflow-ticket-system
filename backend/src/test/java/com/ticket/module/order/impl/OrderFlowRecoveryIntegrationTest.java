package com.ticket.module.order.impl;

import com.ticket.module.order.OrderFlowState;
import com.ticket.module.order.entity.OrderRequest;
import com.ticket.module.order.job.OrderRequestRecoveryJob;
import com.ticket.module.order.mapper.OrderRequestMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证状态流水能够驱动卡死请求进入失败补偿。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderFlowRecoveryIntegrationTest {

    private static final String REQUEST_ID =
            "__flow_recovery_" + System.nanoTime() + "__";
    private static final LocalDate TEST_DATE = LocalDate.of(2099, 11, 1);

    @Autowired
    private OrderRequestFlowService flowService;

    @Autowired
    private OrderRequestRecoveryJob recoveryJob;

    @Autowired
    private OrderRequestMapper requestMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanup();
        jdbcTemplate.update("""
                UPDATE maintenance_cursor
                SET last_event_id = COALESCE(
                    (SELECT MAX(event_id) FROM order_flow_event), 0)
                WHERE job_name = 'ORDER_FLOW'
                """);
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
    void shouldRecordTransitionsAndRecoverStaleCreatedRequest() {
        insertStaleRequest();
        recoveryJob.fullAudit();
        assertRecovered();
    }

    @Test
    void shouldRecoverByPersistedEventCursor() {
        insertStaleRequest();
        jdbcTemplate.update("""
                UPDATE order_flow_event
                SET created_at = DATE_SUB(NOW(), INTERVAL 20 MINUTE)
                WHERE request_id = ?
                """, REQUEST_ID);

        recoveryJob.recoverByEvents();
        assertRecovered();
    }

    @Test
    void shouldKeepRequestActiveUntilRedisStockIsReleased() {
        insertStaleRequest();

        assertTrue(flowService.markReleasing(
                REQUEST_ID,
                OrderFlowState.CREATED.name(),
                "模拟 Redis 释放前的失败窗口",
                java.time.Duration.ofSeconds(30)));
        assertEquals(1, requestMapper.countActiveReservations(
                TEST_DATE, 1L));

        Map<String, Object> releasing = jdbcTemplate.queryForMap("""
                SELECT status, flow_state, stock_released
                FROM order_request
                WHERE request_id = ?
                """, REQUEST_ID);
        assertEquals(5, ((Number) releasing.get("status")).intValue());
        assertEquals("RELEASING", releasing.get("flow_state"));
        assertEquals(0,
                ((Number) releasing.get("stock_released")).intValue());

        assertTrue(flowService.markStockReleased(REQUEST_ID));
        assertEquals(0, requestMapper.countActiveReservations(
                TEST_DATE, 1L));
        assertRecovered();
    }

    private void insertStaleRequest() {
        OrderRequest request = new OrderRequest();
        request.setRequestId(REQUEST_ID);
        request.setUserId(1L);
        request.setPayloadJson("{}");
        request.setTrainId(1L);
        request.setTravelDate(TEST_DATE);
        request.setSeatType(3);
        request.setFromSegmentIndex(1);
        request.setToSegmentIndex(4);
        request.setPassengerCount(1);
        request.setStatus(0);
        request.setStockReserved(0);
        request.setStockReleased(0);
        request.setFlowState(OrderFlowState.CREATED.name());
        flowService.create(request);

        assertEquals(1, eventCount());

        jdbcTemplate.update("""
                UPDATE order_request
                SET updated_at = DATE_SUB(NOW(), INTERVAL 20 MINUTE)
                WHERE request_id = ?
                """, REQUEST_ID);
    }

    private void assertRecovered() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT status, flow_state, stock_released
                FROM order_request
                WHERE request_id = ?
                """, REQUEST_ID);
        assertEquals(4, ((Number) row.get("status")).intValue());
        assertEquals("FAILED", row.get("flow_state"));
        assertEquals(1, ((Number) row.get("stock_released")).intValue());
        assertTrue(eventCount() >= 3);
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM order_flow_event WHERE request_id = ?
                """, REQUEST_ID);
        jdbcTemplate.update("""
                DELETE FROM order_request WHERE request_id = ?
                """, REQUEST_ID);
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = 1
                """, TEST_DATE);
    }

    private int eventCount() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_flow_event
                WHERE request_id = ?
                """, Integer.class, REQUEST_ID);
        return count == null ? 0 : count;
    }
}
