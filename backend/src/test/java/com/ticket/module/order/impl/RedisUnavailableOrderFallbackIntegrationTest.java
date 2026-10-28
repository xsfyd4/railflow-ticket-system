package com.ticket.module.order.impl;

import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Redis 全停时，同步下单仍必须由 MySQL 事务和唯一键完成。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ticket.redis.stock.port=6395",
                "ticket.redis.cache.port=6396",
                "ticket.redis.command-timeout-ms=200",
                "ticket.redis.cache.replica-nodes=localhost:6397,localhost:6398",
                "ticket.redis.reconcile.enabled=false",
                "spring.rabbitmq.listener.simple.auto-startup=false"
        })
class RedisUnavailableOrderFallbackIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 10, 1);
    private static final String USERNAME = "__redis_off_order__";

    @Autowired
    private OrderCreateService orderCreateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long passengerId;

    @BeforeEach
    void setUp() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user (username, password, status)
                VALUES (?, 'not-used', 1)
                """, USERNAME);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?",
                Long.class, USERNAME);
        jdbcTemplate.update("""
                INSERT INTO passenger
                    (user_id, name, id_card_no, passenger_type)
                VALUES (?, ?, ?, 1)
                """, userId, "Redis降级测试", "110101199002019099");
        passengerId = jdbcTemplate.queryForObject("""
                SELECT id FROM passenger
                WHERE user_id = ? AND id_card_no = ?
                """, Long.class, userId, "110101199002019099");
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
        LoginUserContext.set(
                new LoginUser(userId, USERNAME, "USER"));
    }

    @AfterEach
    void tearDown() {
        LoginUserContext.clear();
        cleanup();
    }

    @Test
    void shouldCreateOrderWhenMasterAndReplicasAreUnavailable() {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setTrainId(1L);
        dto.setTravelDate(TEST_DATE);
        dto.setFromStationId(1L);
        dto.setToStationId(27L);
        dto.setSeatType(3);
        dto.setPassengerIds(List.of(passengerId));

        CreateOrderVO result = orderCreateService.submit(
                "__redis_off_sync_order__", dto);

        assertNotNull(result.getOrderNo());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ticket_order
                WHERE user_id = ? AND request_id = ?
                """, Integer.class, userId, "__redis_off_sync_order__"));
        assertEquals(9, jdbcTemplate.queryForObject("""
                SELECT MIN(remaining_count) FROM ticket_stock
                WHERE travel_date = ? AND train_id = 1 AND seat_type = 3
                """, Integer.class, TEST_DATE));
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM ticket_order_item
                WHERE order_id IN (
                    SELECT id FROM ticket_order
                    WHERE user_id IN (
                        SELECT id FROM sys_user WHERE username = ?
                    )
                )
                """, USERNAME);
        jdbcTemplate.update("""
                DELETE FROM ticket_order
                WHERE user_id IN (
                    SELECT id FROM sys_user WHERE username = ?
                )
                """, USERNAME);
        jdbcTemplate.update("""
                DELETE FROM passenger
                WHERE user_id IN (
                    SELECT id FROM sys_user WHERE username = ?
                )
                """, USERNAME);
        jdbcTemplate.update(
                "DELETE FROM sys_user WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = 1
                """, TEST_DATE);
    }
}
