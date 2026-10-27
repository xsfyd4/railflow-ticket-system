package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 同步下单幂等集成测试。
 *
 * <p>异步下单已经由 order_request 做过幂等；这里覆盖的是仍然走
 * {@code POST /api/user/orders} 的同步链路：
 * 同一个 Idempotency-Key 重复提交，只能生成一张订单、只能扣一次库存。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderCreateIdempotencyIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 4, 1);
    private static final Long TRAIN_ID = 1L;
    private static final Long FROM_STATION_ID = 1L;
    private static final Long TO_STATION_ID = 27L;
    private static final Integer SEAT_TYPE = 3;

    private static final String USER_A = "__create_idem_a__";
    private static final String USER_B = "__create_idem_b__";

    @Autowired
    private OrderCreateService orderCreateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void tearDown() {
        LoginUserContext.clear();
        cleanup();
    }

    @Test
    void shouldReturnSameOrderWhenSubmittingTwiceWithSameKey() {
        UserPassenger user = createUserAndPassenger(
                USER_A, "110101199002019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), USER_A, "USER"));

        CreateOrderVO first = orderCreateService.submit(
                "create-key-1", dto(user, 3));
        CreateOrderVO second = orderCreateService.submit(
                "create-key-1", dto(user, 3));

        assertEquals(first.getOrderNo(), second.getOrderNo(),
                "同一幂等键必须返回同一张订单");
        assertEquals(1, count(
                "SELECT COUNT(*) FROM ticket_order WHERE request_id = ?",
                "create-key-1"));
        assertEquals(List.of(9, 9, 9, 9), remaining(),
                "重复提交不能重复扣库存");
    }

    @Test
    void shouldCreateOnlyOneOrderWhenSameKeySubmitsConcurrently()
            throws Exception {
        UserPassenger user = createUserAndPassenger(
                USER_A, "110101199002019001");
        prepareStock(10);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CreateOrderVO> a = pool.submit(
                    () -> createAs(user, "race-key", 3, start));
            Future<CreateOrderVO> b = pool.submit(
                    () -> createAs(user, "race-key", 3, start));
            start.countDown();

            CreateOrderVO first = a.get(15, TimeUnit.SECONDS);
            CreateOrderVO second = b.get(15, TimeUnit.SECONDS);

            assertEquals(first.getOrderNo(), second.getOrderNo());
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM ticket_order WHERE request_id = ?",
                    "race-key"));
            assertEquals(List.of(9, 9, 9, 9), remaining());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldRejectSameKeyWithDifferentRequest() {
        UserPassenger user = createUserAndPassenger(
                USER_A, "110101199002019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), USER_A, "USER"));

        orderCreateService.submit("payload-key", dto(user, 3));

        CreateOrderDTO changed = dto(user, 2);
        BizException error = assertThrows(BizException.class,
                () -> orderCreateService.submit("payload-key", changed));

        assertEquals(2019, error.getCode(),
                "同一幂等键不能换参数重复使用");
    }

    @Test
    void shouldRejectKeyOwnedByAnotherUser() {
        UserPassenger a = createUserAndPassenger(
                USER_A, "110101199002019001");
        UserPassenger b = createUserAndPassenger(
                USER_B, "110101199002019002");
        prepareStock(10);

        LoginUserContext.set(new LoginUser(a.userId(), USER_A, "USER"));
        orderCreateService.submit("shared-create-key", dto(a, 3));

        LoginUserContext.set(new LoginUser(b.userId(), USER_B, "USER"));
        BizException error = assertThrows(BizException.class,
                () -> orderCreateService.submit(
                        "shared-create-key", dto(b, 3)));

        assertEquals(2019, error.getCode(),
                "不能通过幂等键读到别人的订单");
    }

    @Test
    void shouldRequireIdempotencyKey() {
        UserPassenger user = createUserAndPassenger(
                USER_A, "110101199002019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), USER_A, "USER"));

        BizException error = assertThrows(BizException.class,
                () -> orderCreateService.submit(null, dto(user, 3)));

        assertEquals(400, error.getCode());
        assertEquals(0, count(
                "SELECT COUNT(*) FROM ticket_order WHERE user_id = ?",
                user.userId()));
    }

    @Test
    void shouldCreateTwoOrdersForDifferentKeys() {
        UserPassenger user = createUserAndPassenger(
                USER_A, "110101199002019001");
        prepareStock(10);
        LoginUserContext.set(new LoginUser(user.userId(), USER_A, "USER"));

        CreateOrderVO first = orderCreateService.submit(
                "key-a", dto(user, 3));
        CreateOrderVO second = orderCreateService.submit(
                "key-b", dto(user, 3));

        assertEquals(2, count(
                "SELECT COUNT(*) FROM ticket_order WHERE user_id = ?",
                user.userId()));
        assertEquals(false, first.getOrderNo().equals(second.getOrderNo()),
                "不同幂等键代表两次独立下单");
    }

    // -------------------------------------------------------------- 辅助方法

    private CreateOrderVO createAs(UserPassenger user,
                                   String key,
                                   int seatType,
                                   CountDownLatch start)
            throws InterruptedException {
        LoginUserContext.set(
                new LoginUser(user.userId(), USER_A, "USER"));
        try {
            start.await();
            return orderCreateService.submit(key, dto(user, seatType));
        } finally {
            LoginUserContext.clear();
        }
    }

    private CreateOrderDTO dto(UserPassenger user, int seatType) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setTrainId(TRAIN_ID);
        dto.setTravelDate(TEST_DATE);
        dto.setFromStationId(FROM_STATION_ID);
        dto.setToStationId(TO_STATION_ID);
        dto.setSeatType(seatType);
        dto.setPassengerIds(List.of(user.passengerId()));
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
        jdbcTemplate.update("""
                INSERT INTO passenger
                    (user_id, name, id_card_no, passenger_type)
                VALUES (?, ?, ?, 1)
                """, userId, "下单幂等测试", idCardNo);
        Long passengerId = jdbcTemplate.queryForObject("""
                SELECT id FROM passenger
                WHERE user_id = ? AND id_card_no = ?
                """, Long.class, userId, idCardNo);
        return new UserPassenger(userId, passengerId);
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

    private List<Integer> remaining() {
        return jdbcTemplate.queryForList("""
                SELECT remaining_count
                FROM ticket_stock
                WHERE travel_date = ?
                  AND train_id = ?
                  AND seat_type = ?
                ORDER BY segment_index
                """, Integer.class, TEST_DATE, TRAIN_ID, SEAT_TYPE);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM ticket_order_item
                WHERE order_id IN (
                    SELECT id FROM ticket_order
                    WHERE user_id IN (
                        SELECT id FROM sys_user WHERE username IN (?, ?)
                    )
                )
                """, USER_A, USER_B);
        jdbcTemplate.update("""
                DELETE FROM ticket_order
                WHERE user_id IN (
                    SELECT id FROM sys_user WHERE username IN (?, ?)
                )
                """, USER_A, USER_B);
        jdbcTemplate.update("""
                DELETE FROM passenger
                WHERE user_id IN (
                    SELECT id FROM sys_user WHERE username IN (?, ?)
                )
                """, USER_A, USER_B);
        jdbcTemplate.update(
                "DELETE FROM sys_user WHERE username IN (?, ?)",
                USER_A, USER_B);
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = ?
                """, TEST_DATE, TRAIN_ID);
    }

    private record UserPassenger(Long userId, Long passengerId) {
    }
}
