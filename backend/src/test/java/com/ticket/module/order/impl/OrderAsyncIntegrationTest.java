package com.ticket.module.order.impl;

import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.vo.AsyncOrderVO;
import com.ticket.module.stock.TicketStockCache;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实经过 Redis 预扣、RabbitMQ 投递、消费者落库和状态查询的端到端测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderAsyncIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 7, 1);
    private static final Long TRAIN_ID = 1L;
    private static final Long FROM_STATION_ID = 1L;
    private static final Long TO_STATION_ID = 27L;
    private static final Integer SEAT_TYPE = 3;
    private static final String USERNAME = "__async_order_test__";

    @Autowired
    private OrderAsyncServiceImpl asyncOrderService;

    @Autowired
    private AsyncOrderPublisher publisher;

    @Autowired
    private TicketStockCache stockCache;

    @Autowired
    private StringRedisTemplate redis;

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
                INSERT INTO passenger (user_id, name, id_card_no, passenger_type)
                VALUES (?, '异步测试', '110101199001019999', 1)
                """, userId);
        passengerId = jdbcTemplate.queryForObject("""
                SELECT id FROM passenger
                WHERE user_id = ? AND id_card_no = '110101199001019999'
                """, Long.class, userId);

        jdbcTemplate.update("""
                INSERT INTO ticket_stock
                    (travel_date, train_id, segment_index, seat_type,
                     from_station_id, to_station_id,
                     total_count, sold_count, remaining_count)
                SELECT ?, seg.train_id, seg.segment_index, ?,
                       seg.from_station_id, seg.to_station_id,
                       3, 0, 3
                FROM train_segment seg
                WHERE seg.train_id = ?
                """, TEST_DATE, SEAT_TYPE, TRAIN_ID);

        LoginUserContext.set(new LoginUser(
                userId, USERNAME, "USER"));
    }

    @AfterEach
    void tearDown() {
        LoginUserContext.clear();
        cleanup();
    }

    @Test
    void shouldCreateOneOrderThroughRedisAndRabbitMqIdempotently()
            throws Exception {
        String requestId = "async-test-" + System.nanoTime();
        CreateOrderDTO dto = dto();

        AsyncOrderVO accepted =
                asyncOrderService.submit(requestId, dto);
        assertEquals(requestId, accepted.getRequestId());
        assertTrue(accepted.getStatus() >= 1 && accepted.getStatus() <= 3);

        AsyncOrderVO completed = waitForSuccess(requestId);
        assertEquals(3, completed.getStatus());
        assertNotNull(completed.getOrderNo());

        // 用同一个幂等键再次提交，不重复扣库存、不生成第二笔订单。
        AsyncOrderVO replay = asyncOrderService.submit(requestId, dto);
        assertEquals(3, replay.getStatus());
        assertEquals(completed.getOrderNo(), replay.getOrderNo());

        // 模拟 RabbitMQ 至少一次投递：重复消息也必须被终态和数据库行锁挡住。
        publisher.publish(requestId);
        Thread.sleep(500);

        assertEquals(List.of(2, 2, 2, 2), mysqlCounts());
        assertEquals(List.of("2", "2", "2", "2"), redisCounts());
        assertEquals(1, count("""
                SELECT COUNT(*) FROM order_request
                WHERE user_id = ?
                """, userId));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM ticket_order
                WHERE user_id = ?
                """, userId));
    }

    private AsyncOrderVO waitForSuccess(String requestId)
            throws InterruptedException {
        AsyncOrderVO latest = null;
        for (int i = 0; i < 100; i++) {
            latest = asyncOrderService.status(requestId);
            if (latest.getStatus() == 3 || latest.getStatus() == 4) {
                return latest;
            }
            Thread.sleep(100);
        }
        return latest;
    }

    private CreateOrderDTO dto() {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setTrainId(TRAIN_ID);
        dto.setTravelDate(TEST_DATE);
        dto.setFromStationId(FROM_STATION_ID);
        dto.setToStationId(TO_STATION_ID);
        dto.setSeatType(SEAT_TYPE);
        dto.setPassengerIds(List.of(passengerId));
        return dto;
    }

    private List<Integer> mysqlCounts() {
        return jdbcTemplate.queryForList("""
                SELECT remaining_count
                FROM ticket_stock
                WHERE travel_date = ? AND train_id = ? AND seat_type = ?
                ORDER BY segment_index
                """, Integer.class, TEST_DATE, TRAIN_ID, SEAT_TYPE);
    }

    private List<String> redisCounts() {
        List<String> values = new ArrayList<>();
        for (int segment = 1; segment <= 4; segment++) {
            values.add(redis.opsForValue().get(stockCache.key(
                    TEST_DATE, TRAIN_ID, SEAT_TYPE, segment)));
        }
        return values;
    }

    private int count(String sql, Object... args) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM ticket_order_item
                WHERE order_id IN (
                    SELECT id FROM ticket_order WHERE user_id IN (
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
                DELETE FROM order_request
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
                WHERE travel_date = ? AND train_id = ?
                """, TEST_DATE, TRAIN_ID);

        List<String> keys = new ArrayList<>();
        for (int segment = 1; segment <= 4; segment++) {
            keys.add(stockCache.key(
                    TEST_DATE, TRAIN_ID, SEAT_TYPE, segment));
        }
        redis.delete(keys);
    }
}
