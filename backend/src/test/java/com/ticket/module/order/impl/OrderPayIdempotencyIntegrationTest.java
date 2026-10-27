package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.module.order.OrderService;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.module.order.vo.OrderActionResultVO;
import com.ticket.module.order.vo.PayLogVO;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付幂等集成测试。
 *
 * <p>验证的是“钱只能扣一次”这条底线：
 * <ul>
 *   <li>同一幂等键重复支付：返回同一个 pay_no；</li>
 *   <li>不同幂等键重复支付：订单只成功一次，另一条流水置为已关闭；</li>
 *   <li>并发支付：无论同键还是不同键，成功流水有且只有一条；</li>
 *   <li>超时/取消后再支付：拒绝，并留下失败流水，便于对账；</li>
 *   <li>幂等键被别的订单占用：明确报错，不能串单。</li>
 * </ul>
 *
 * <p>运行前提：MySQL 已执行 {@code sql/04-pay-log.sql}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderPayIdempotencyIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 3, 1);
    private static final Long TRAIN_ID = 1L;
    private static final Long FROM_STATION_ID = 1L;
    private static final Long TO_STATION_ID = 27L;
    private static final Integer SEAT_TYPE = 3;

    private static final String PAY_USER = "__pay_test_primary__";
    private static final String PAY_USER_TWO = "__pay_test_second__";

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TicketStockCache stockCache;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void tearDown() {
        LoginUserContext.clear();
        cleanup();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void shouldReturnSamePayNoWhenPayingTwiceWithSameKey() {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));

        OrderActionResultVO first = orderService.pay(
                order.getOrderNo(), "pay-key-1", null);
        OrderActionResultVO second = orderService.pay(
                order.getOrderNo(), "pay-key-1", null);

        assertTrue(first.getPayNo() != null);
        assertEquals(first.getPayNo(), second.getPayNo(),
                "同一幂等键必须返回同一支付流水号");
        assertFalse(Boolean.TRUE.equals(first.getIdempotentReplay()));
        assertTrue(Boolean.TRUE.equals(second.getIdempotentReplay()),
                "第二次必须是幂等重放");

        assertEquals(1, count(
                "SELECT COUNT(*) FROM pay_log WHERE order_no = ?",
                order.getOrderNo()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM pay_log
                WHERE order_no = ? AND status = 1
                """, order.getOrderNo()));
        assertEquals(1, count(
                "SELECT status FROM ticket_order WHERE order_no = ?",
                order.getOrderNo()));
    }

    @Test
    void shouldCloseSecondAttemptWhenPayingAgainWithNewKey() {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));

        OrderActionResultVO first = orderService.pay(
                order.getOrderNo(), "pay-key-1", null);
        OrderActionResultVO second = orderService.pay(
                order.getOrderNo(), "pay-key-2", null);

        assertEquals(first.getPayNo(), second.getPayNo(),
                "第二次支付必须返回首次成功的流水号");
        assertTrue(Boolean.TRUE.equals(second.getIdempotentReplay()));

        assertEquals(2, count(
                "SELECT COUNT(*) FROM pay_log WHERE order_no = ?",
                order.getOrderNo()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM pay_log
                WHERE order_no = ? AND status = 1
                """, order.getOrderNo()),
                "成功流水只能有一条");
        assertEquals(1, count("""
                SELECT COUNT(*) FROM pay_log
                WHERE order_no = ? AND status = 3
                """, order.getOrderNo()),
                "后到的支付尝试应被关闭");
    }

    @Test
    void shouldAllowOnlyOneSuccessWhenSameKeyPaysConcurrently()
            throws Exception {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OrderActionResultVO> a = pool.submit(
                    () -> payAs(user, order.getOrderNo(), "same-key", start));
            Future<OrderActionResultVO> b = pool.submit(
                    () -> payAs(user, order.getOrderNo(), "same-key", start));
            start.countDown();

            OrderActionResultVO first = a.get(10, TimeUnit.SECONDS);
            OrderActionResultVO second = b.get(10, TimeUnit.SECONDS);

            assertEquals(first.getPayNo(), second.getPayNo(),
                    "并发同键支付必须拿到同一个流水号");
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM pay_log WHERE order_no = ?",
                    order.getOrderNo()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldAllowOnlyOneSuccessWhenDifferentKeysPayConcurrently()
            throws Exception {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OrderActionResultVO> a = pool.submit(
                    () -> payAs(user, order.getOrderNo(), "key-a", start));
            Future<OrderActionResultVO> b = pool.submit(
                    () -> payAs(user, order.getOrderNo(), "key-b", start));
            start.countDown();

            OrderActionResultVO first = a.get(10, TimeUnit.SECONDS);
            OrderActionResultVO second = b.get(10, TimeUnit.SECONDS);

            assertNotNull(first.getPayNo());
            assertEquals(first.getPayNo(), second.getPayNo(),
                    "输掉的支付尝试也必须返回赢家的流水号");
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM pay_log
                    WHERE order_no = ? AND status = 1
                    """, order.getOrderNo()));
            assertEquals(2, count(
                    "SELECT COUNT(*) FROM pay_log WHERE order_no = ?",
                    order.getOrderNo()));
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM pay_log
                    WHERE order_no = ? AND status = 3
                    """, order.getOrderNo()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRejectExpiredOrderAndRecordFailedLog() {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));
        jdbcTemplate.update("""
                UPDATE ticket_order
                SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE)
                WHERE order_no = ?
                """, order.getOrderNo());

        BizException error = assertThrows(BizException.class,
                () -> orderService.pay(order.getOrderNo(), "expired-key", null));

        assertEquals(2003, error.getCode());
        assertEquals(1, count("""
                SELECT COUNT(*) FROM pay_log
                WHERE order_no = ? AND status = 2
                """, order.getOrderNo()),
                "超时支付也要留下失败流水，方便对账");
    }

    @Test
    void shouldRejectCancelledOrderAndRecordFailedLog() {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));
        orderService.cancel(order.getOrderNo());

        BizException error = assertThrows(BizException.class,
                () -> orderService.pay(order.getOrderNo(), "cancelled-key", null));

        assertEquals(2002, error.getCode());
        assertEquals(1, count("""
                SELECT COUNT(*) FROM pay_log
                WHERE order_no = ? AND status = 2
                """, order.getOrderNo()));
    }

    @Test
    void shouldRejectReusingRequestIdForAnotherOrder() {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        UserPassenger companion = new UserPassenger(user.userId(),
                createPassenger(user.userId(), "测试同行人",
                        "110101199001019002"));
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO firstOrder = orderService.create(
                dto(List.of(user.passengerId())));
        CreateOrderVO secondOrder = orderService.create(
                dto(List.of(companion.passengerId())));

        orderService.pay(firstOrder.getOrderNo(), "shared-key", null);

        BizException conflict = assertThrows(BizException.class,
                () -> orderService.pay(
                        secondOrder.getOrderNo(), "shared-key", null));
        assertEquals(2010, conflict.getCode(),
                "同一幂等键不能用于另一张订单");
    }

    @Test
    void shouldExposeAllPaymentAttempts() {
        UserPassenger user = createUserAndPassenger(
                PAY_USER, "110101199001019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), PAY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));

        orderService.pay(order.getOrderNo(), "list-key-1", null);
        orderService.pay(order.getOrderNo(), "list-key-2", null);

        List<PayLogVO> logs = orderService.payments(order.getOrderNo());

        assertEquals(2, logs.size());
        assertTrue(logs.stream().anyMatch(l -> l.getStatus() == 1));
        assertTrue(logs.stream().anyMatch(l -> l.getStatus() == 3));
        assertTrue(logs.stream().allMatch(l -> l.getPayNo() != null));
    }

    // -------------------------------------------------------------- 辅助方法

    private OrderActionResultVO payAs(UserPassenger user,
                                      String orderNo,
                                      String key,
                                      CountDownLatch start)
            throws InterruptedException {
        LoginUserContext.set(
                new LoginUser(user.userId(), PAY_USER, "USER"));
        try {
            start.await();
            return orderService.pay(orderNo, key, null);
        } finally {
            LoginUserContext.clear();
        }
    }

    private CreateOrderDTO dto(List<Long> passengerIds) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setTrainId(TRAIN_ID);
        dto.setTravelDate(TEST_DATE);
        dto.setFromStationId(FROM_STATION_ID);
        dto.setToStationId(TO_STATION_ID);
        dto.setSeatType(SEAT_TYPE);
        dto.setPassengerIds(passengerIds);
        return dto;
    }

    private UserPassenger createUserAndPassenger(String username,
                                                 String idCardNo) {
        jdbcTemplate.update("""
                INSERT INTO sys_user (username, password, status)
                VALUES (?, 'not-used', 1)
                """, username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?",
                Long.class, username);
        Long passengerId = createPassenger(userId, "支付测试乘车人", idCardNo);
        return new UserPassenger(userId, passengerId);
    }

    private Long createPassenger(Long userId, String name, String idCardNo) {
        jdbcTemplate.update("""
                INSERT INTO passenger
                    (user_id, name, id_card_no, passenger_type)
                VALUES (?, ?, ?, 1)
                """, userId, name, idCardNo);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM passenger
                WHERE user_id = ? AND id_card_no = ?
                """, Long.class, userId, idCardNo);
    }

    private void prepareStock(int capacity) {
        jdbcTemplate.update("""
                INSERT INTO ticket_stock
                    (travel_date, train_id, segment_index, seat_type,
                     from_station_id, to_station_id,
                     total_count, sold_count, remaining_count)
                SELECT ?, seg.train_id, seg.segment_index, ?,
                       seg.from_station_id, seg.to_station_id,
                       ?, 0, ?
                FROM train_segment seg
                WHERE seg.train_id = ?
                ORDER BY seg.segment_index
                """, TEST_DATE, SEAT_TYPE, capacity, capacity, TRAIN_ID);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM pay_log
                WHERE user_id IN (
                    SELECT id FROM sys_user WHERE username IN (?, ?)
                )
                """, PAY_USER, PAY_USER_TWO);
        jdbcTemplate.update("""
                DELETE FROM ticket_order_item
                WHERE order_id IN (
                    SELECT id FROM ticket_order
                    WHERE user_id IN (
                        SELECT id FROM sys_user WHERE username IN (?, ?)
                    )
                )
                """, PAY_USER, PAY_USER_TWO);
        jdbcTemplate.update("""
                DELETE FROM ticket_order
                WHERE user_id IN (
                    SELECT id FROM sys_user WHERE username IN (?, ?)
                )
                """, PAY_USER, PAY_USER_TWO);
        jdbcTemplate.update("""
                DELETE FROM passenger
                WHERE user_id IN (
                    SELECT id FROM sys_user WHERE username IN (?, ?)
                )
                """, PAY_USER, PAY_USER_TWO);
        jdbcTemplate.update(
                "DELETE FROM sys_user WHERE username IN (?, ?)",
                PAY_USER, PAY_USER_TWO);
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = ?
                """, TEST_DATE, TRAIN_ID);
        List<String> stockKeys = new java.util.ArrayList<>();
        for (int segment = 1; segment <= 4; segment++) {
            stockKeys.add(stockCache.key(
                    TEST_DATE, TRAIN_ID, SEAT_TYPE, segment));
        }
        redis.delete(stockKeys);
    }

    private record UserPassenger(Long userId, Long passengerId) {
    }
}
