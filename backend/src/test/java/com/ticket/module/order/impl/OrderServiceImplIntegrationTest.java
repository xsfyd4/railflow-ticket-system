package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.PageResult;
import com.ticket.module.order.OrderService;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.dto.OrderQueryDTO;
import com.ticket.module.order.dto.RefundOrderDTO;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.module.order.vo.OrderActionResultVO;
import com.ticket.module.order.vo.OrderDetailVO;
import com.ticket.module.order.vo.OrderVO;
import com.ticket.module.order.vo.RefundResultVO;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderServiceImplIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 2, 1);
    private static final Long TRAIN_ID = 1L;
    private static final Long FROM_STATION_ID = 1L;
    private static final Long TO_STATION_ID = 27L;
    private static final Integer SEAT_TYPE = 3;
    private static final String PRIMARY_USER = "__order_test_primary__";
    private static final String SECOND_USER = "__order_test_second__";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderExpireTxService expireTxService;

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

    @Test
    void shouldCreateOrderAndDeductEveryCoveredSegment() {
        UserPassenger primary =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger companion =
                createUserPassengerForUser(primary.userId(), "李四",
                        "110101199001011242");
        prepareStock(10);
        BigDecimal pricePerPassenger = queryPrice();

        LoginUserContext.set(
                new LoginUser(primary.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO result = orderService.create(dto(
                List.of(primary.passengerId(), companion.passengerId())));

        assertTrue(result.getOrderId() > 0);
        assertEquals(26, result.getOrderNo().length());
        assertEquals(0, result.getStatus());
        assertEquals(0, pricePerPassenger.multiply(BigDecimal.valueOf(2))
                .compareTo(result.getTotalAmount()));

        assertEquals(List.of(8, 8, 8, 8), remainingCounts());
        assertEquals(2, count(
                "SELECT COUNT(*) FROM ticket_order_item WHERE order_id = ?",
                result.getOrderId()));

        OrderQueryDTO query = new OrderQueryDTO();
        query.setStatus(0);
        PageResult<OrderVO> page = orderService.page(query);
        assertEquals(1, page.getTotal());
        assertEquals("G1001", page.getRecords().get(0).getTrainNo());
        assertEquals("北京南", page.getRecords().get(0).getFromStationName());
        assertEquals("上海虹桥", page.getRecords().get(0).getToStationName());

        OrderDetailVO detail = orderService.detail(result.getOrderNo());
        assertEquals(result.getOrderNo(), detail.getOrderNo());
        assertEquals(2, detail.getItems().size());
        assertEquals("110***********1234",
                detail.getItems().get(0).getIdCardNo(),
                "订单详情必须返回脱敏证件号");
    }

    @Test
    void shouldRollbackStockWhenAnySegmentIsNotEnough() {
        UserPassenger primary =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger companion =
                createUserPassengerForUser(primary.userId(), "李四",
                        "110101199001011242");
        prepareStock(10);
        jdbcTemplate.update("""
                UPDATE ticket_stock
                SET remaining_count = 1
                WHERE travel_date = ?
                  AND train_id = ?
                  AND seat_type = ?
                  AND segment_index = 3
                """, TEST_DATE, TRAIN_ID, SEAT_TYPE);

        LoginUserContext.set(
                new LoginUser(primary.userId(), PRIMARY_USER, "USER"));
        BizException error = assertThrows(BizException.class,
                () -> orderService.create(dto(
                        List.of(primary.passengerId(), companion.passengerId()))));

        assertEquals(2001, error.getCode());
        assertEquals(List.of(10, 10, 1, 10), remainingCounts(),
                "前面几段即使先扣成功，也必须随事务一起回滚");
        assertEquals(0, count(
                "SELECT COUNT(*) FROM ticket_order WHERE user_id = ?",
                primary.userId()));
    }

    @Test
    void shouldRejectMixedOwnershipInOneBatchPassengerQuery() {
        UserPassenger owner =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger other =
                createUserAndPassenger(SECOND_USER, "110101199001011242");
        prepareStock(10);

        LoginUserContext.set(
                new LoginUser(owner.userId(), PRIMARY_USER, "USER"));
        BizException error = assertThrows(BizException.class,
                () -> orderService.create(dto(List.of(
                        owner.passengerId(), other.passengerId()))));

        assertEquals(4001, error.getCode());
        assertEquals(List.of(10, 10, 10, 10), remainingCounts());
        assertEquals(0, count("""
                SELECT COUNT(*) FROM ticket_order WHERE user_id = ?
                """, owner.userId()));
    }

    @Test
    void shouldNotOversellWhenTwoRequestsCompeteForLastTicket()
            throws Exception {
        UserPassenger first =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger second =
                createUserAndPassenger(SECOND_USER, "110101199001011242");
        prepareStock(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> firstResult = pool.submit(
                    () -> placeOrder(first, start));
            Future<Boolean> secondResult = pool.submit(
                    () -> placeOrder(second, start));

            start.countDown();
            int successCount = (firstResult.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (secondResult.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, successCount, "最后一张票只能有一个请求成功");
            assertEquals(List.of(0, 0, 0, 0), remainingCounts());
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM ticket_order WHERE travel_date = ?"
                            + " AND train_id = ?",
                    TEST_DATE, TRAIN_ID));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldAllowOnlyOneWinnerAmongManyConcurrentOrders()
            throws Exception {
        prepareStock(1);
        List<UserPassenger> users = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String suffix = String.format("%02d", i);
            users.add(createUserAndPassenger(
                    "load_order_test_" + suffix,
                    String.format("11010119900101%04d", 2000 + i)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(users.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = users.stream()
                    .map(user -> pool.submit(() -> placeOrder(user, start)))
                    .toList();
            start.countDown();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            assertEquals(1, successCount, "16 个并发请求只能有一个抢到票");
            assertEquals(List.of(0, 0, 0, 0), remainingCounts());
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM ticket_order WHERE travel_date = ?"
                            + " AND train_id = ?",
                    TEST_DATE, TRAIN_ID));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldNotExposeAnotherUsersOrder() {
        UserPassenger owner =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger other =
                createUserAndPassenger(SECOND_USER, "110101199001011242");
        prepareStock(10);

        LoginUserContext.set(
                new LoginUser(owner.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(owner.passengerId())));

        LoginUserContext.set(
                new LoginUser(other.userId(), SECOND_USER, "USER"));
        BizException error = assertThrows(BizException.class,
                () -> orderService.detail(order.getOrderNo()));
        assertEquals(2006, error.getCode());

        OrderQueryDTO query = new OrderQueryDTO();
        assertEquals(0, orderService.page(query).getTotal(),
                "其他用户不能在自己的订单列表看到这笔订单");
    }

    @Test
    void shouldPayPendingOrderOnlyOnce() {
        UserPassenger user =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(user.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));

        OrderActionResultVO result = orderService.pay(order.getOrderNo());
        assertEquals(1, result.getStatus());
        assertTrue(result.getPaidAt() != null);
        assertEquals(1, count(
                "SELECT status FROM ticket_order WHERE order_no = ?",
                order.getOrderNo()));

        /*
         * 支付必须幂等：重复点击不能报错让用户以为没付成功，
         * 也不能再扣一次钱，而是返回首次成功的同一个流水号。
         */
        OrderActionResultVO replay = orderService.pay(order.getOrderNo());
        assertEquals(1, replay.getStatus());
        assertEquals(result.getPayNo(), replay.getPayNo(),
                "重复支付必须返回首次成功的流水号");
        assertTrue(Boolean.TRUE.equals(replay.getIdempotentReplay()),
                "第二次支付必须是幂等重放");
        assertEquals(List.of(9, 9, 9, 9), remainingCounts(),
                "支付不会再次修改库存");
    }

    @Test
    void shouldCancelPendingOrderAndRestoreStockOnlyOnce() {
        UserPassenger primary =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger companion =
                createUserPassengerForUser(primary.userId(), "李四",
                        "110101199001011242");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(primary.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(dto(
                List.of(primary.passengerId(), companion.passengerId())));
        assertEquals(List.of(8, 8, 8, 8), remainingCounts());

        OrderActionResultVO result = orderService.cancel(order.getOrderNo());
        assertEquals(2, result.getStatus());
        assertTrue(result.getCancelledAt() != null);
        assertEquals(List.of(10, 10, 10, 10), remainingCounts());

        BizException error = assertThrows(BizException.class,
                () -> orderService.cancel(order.getOrderNo()));
        assertEquals(2002, error.getCode());
        assertEquals(List.of(10, 10, 10, 10), remainingCounts(),
                "重复取消不能把库存回补两次");
    }

    @Test
    void shouldRestoreStockOnceWhenTwoCancellationsRace() throws Exception {
        UserPassenger user =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(user.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));
        assertEquals(List.of(9, 9, 9, 9), remainingCounts());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(
                    () -> cancelOrder(order.getOrderNo(), user, start));
            Future<Boolean> second = pool.submit(
                    () -> cancelOrder(order.getOrderNo(), user, start));

            start.countDown();
            int successCount = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, successCount, "相同订单只能取消成功一次");
            assertEquals(List.of(10, 10, 10, 10), remainingCounts(),
                    "并发重复取消也只能回补一次库存");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldNotCancelPaidOrder() {
        UserPassenger user =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(user.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));
        orderService.pay(order.getOrderNo());

        BizException error = assertThrows(BizException.class,
                () -> orderService.cancel(order.getOrderNo()));
        assertEquals(2002, error.getCode());
        assertEquals(List.of(9, 9, 9, 9), remainingCounts());
        assertEquals(1, count(
                "SELECT status FROM ticket_order WHERE order_no = ?",
                order.getOrderNo()));
    }

    @Test
    void shouldRejectExpiredPaymentAndAnotherUsersPayment() {
        UserPassenger owner =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger other =
                createUserAndPassenger(SECOND_USER, "110101199001011242");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(owner.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(owner.passengerId())));

        jdbcTemplate.update("""
                UPDATE ticket_order
                SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE)
                WHERE order_no = ?
                """, order.getOrderNo());
        BizException expired = assertThrows(BizException.class,
                () -> orderService.pay(order.getOrderNo()));
        assertEquals(2003, expired.getCode());

        LoginUserContext.set(
                new LoginUser(other.userId(), SECOND_USER, "USER"));
        BizException foreign = assertThrows(BizException.class,
                () -> orderService.pay(order.getOrderNo()));
        assertEquals(2006, foreign.getCode());
    }

    @Test
    void shouldExpirePendingOrderAndRestoreStockOnce() {
        UserPassenger user =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(user.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));
        jdbcTemplate.update("""
                UPDATE ticket_order
                SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE)
                WHERE id = ?
                """, order.getOrderId());

        assertTrue(expireTxService.expireOne(order.getOrderId()));
        assertEquals(2, count(
                "SELECT status FROM ticket_order WHERE id = ?",
                order.getOrderId()));
        assertEquals(List.of(10, 10, 10, 10), remainingCounts());

        // 同一个订单再次被扫描时，状态已经不是待支付，不能再次回补。
        assertFalse(expireTxService.expireOne(order.getOrderId()));
        assertEquals(List.of(10, 10, 10, 10), remainingCounts());
    }

    @Test
    void shouldNotExpireFutureOrPaidOrder() {
        UserPassenger user =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(user.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));

        assertFalse(expireTxService.expireOne(order.getOrderId()),
                "未到支付截止时间不能取消");
        assertEquals(List.of(9, 9, 9, 9), remainingCounts());

        orderService.pay(order.getOrderNo());
        assertFalse(expireTxService.expireOne(order.getOrderId()),
                "已支付订单不能被超时任务取消");
        assertEquals(List.of(9, 9, 9, 9), remainingCounts());
    }

    @Test
    void shouldRefundOnePassengerThenRemainingPassenger() {
        UserPassenger primary =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger companion =
                createUserPassengerForUser(primary.userId(), "李四",
                        "110101199001011242");
        prepareStock(10);
        BigDecimal pricePerPassenger = queryPrice();
        LoginUserContext.set(
                new LoginUser(primary.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(dto(
                List.of(primary.passengerId(), companion.passengerId())));
        orderService.pay(order.getOrderNo());

        OrderDetailVO detail = orderService.detail(order.getOrderNo());
        Long firstItemId = detail.getItems().get(0).getId();
        Long secondItemId = detail.getItems().get(1).getId();

        RefundResultVO partial = orderService.refund(
                order.getOrderNo(), refundDto(List.of(firstItemId)));
        assertEquals(3, partial.getOrderStatus());
        assertEquals(1, partial.getRefundedCount());
        assertEquals(1, partial.getRemainingTicketCount());
        assertEquals(0, pricePerPassenger.compareTo(partial.getRefundedAmount()));
        assertEquals(List.of(9, 9, 9, 9), remainingCounts(),
                "只退一位乘客时，每个分段只回补 1 张");

        OrderDetailVO afterPartial = orderService.detail(order.getOrderNo());
        assertEquals(2, afterPartial.getItems().get(0).getStatus());
        assertTrue(afterPartial.getItems().get(0).getRefundedAt() != null);
        assertEquals(0, afterPartial.getItems().get(1).getStatus());

        RefundResultVO full = orderService.refund(
                order.getOrderNo(), refundDto(List.of(secondItemId)));
        assertEquals(4, full.getOrderStatus());
        assertEquals(2, full.getRefundedCount());
        assertEquals(0, full.getRemainingTicketCount());
        assertEquals(0, pricePerPassenger.multiply(BigDecimal.valueOf(2))
                .compareTo(full.getRefundedAmount()));
        assertEquals(List.of(10, 10, 10, 10), remainingCounts());
    }

    @Test
    void shouldRejectPendingAndDuplicateAndForeignRefund() {
        UserPassenger owner =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        UserPassenger other =
                createUserAndPassenger(SECOND_USER, "110101199001011242");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(owner.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(owner.passengerId())));
        Long itemId = orderService.detail(order.getOrderNo())
                .getItems().get(0).getId();

        BizException pending = assertThrows(BizException.class,
                () -> orderService.refund(
                        order.getOrderNo(), refundDto(List.of(itemId))));
        assertEquals(2002, pending.getCode());

        orderService.pay(order.getOrderNo());
        orderService.refund(order.getOrderNo(), refundDto(List.of(itemId)));
        BizException duplicate = assertThrows(BizException.class,
                () -> orderService.refund(
                        order.getOrderNo(), refundDto(List.of(itemId))));
        assertEquals(2009, duplicate.getCode());
        assertEquals(List.of(10, 10, 10, 10), remainingCounts());

        LoginUserContext.set(
                new LoginUser(other.userId(), SECOND_USER, "USER"));
        BizException foreign = assertThrows(BizException.class,
                () -> orderService.refund(
                        order.getOrderNo(), refundDto(List.of(itemId))));
        assertEquals(2006, foreign.getCode());
    }

    @Test
    void shouldNotDoubleRestoreWhenRefundsRace() throws Exception {
        UserPassenger user =
                createUserAndPassenger(PRIMARY_USER, "110101199001011234");
        prepareStock(10);
        LoginUserContext.set(
                new LoginUser(user.userId(), PRIMARY_USER, "USER"));
        CreateOrderVO order = orderService.create(
                dto(List.of(user.passengerId())));
        orderService.pay(order.getOrderNo());
        Long itemId = orderService.detail(order.getOrderNo())
                .getItems().get(0).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(
                    () -> refundOrder(order.getOrderNo(), itemId, user, start));
            Future<Boolean> second = pool.submit(
                    () -> refundOrder(order.getOrderNo(), itemId, user, start));

            start.countDown();
            int successCount = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, successCount, "同一张票只能退一次");
            assertEquals(List.of(10, 10, 10, 10), remainingCounts(),
                    "并发退同一张票只能回补一次库存");
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean placeOrder(UserPassenger user, CountDownLatch start)
            throws InterruptedException {
        LoginUserContext.set(
                new LoginUser(user.userId(), "order-test", "USER"));
        try {
            start.await();
            orderService.create(dto(List.of(user.passengerId())));
            return true;
        } catch (BizException e) {
            if (e.getCode() == 2001) {
                return false;
            }
            throw e;
        } finally {
            LoginUserContext.clear();
        }
    }

    private boolean cancelOrder(String orderNo,
                                UserPassenger user,
                                CountDownLatch start)
            throws InterruptedException {
        LoginUserContext.set(
                new LoginUser(user.userId(), "order-test", "USER"));
        try {
            start.await();
            orderService.cancel(orderNo);
            return true;
        } catch (BizException e) {
            if (e.getCode() == 2002) {
                return false;
            }
            throw e;
        } finally {
            LoginUserContext.clear();
        }
    }

    private boolean refundOrder(String orderNo,
                                Long itemId,
                                UserPassenger user,
                                CountDownLatch start)
            throws InterruptedException {
        LoginUserContext.set(
                new LoginUser(user.userId(), "order-test", "USER"));
        try {
            start.await();
            orderService.refund(orderNo, refundDto(List.of(itemId)));
            return true;
        } catch (BizException e) {
            if (e.getCode() == 2009) {
                return false;
            }
            throw e;
        } finally {
            LoginUserContext.clear();
        }
    }

    private RefundOrderDTO refundDto(List<Long> itemIds) {
        RefundOrderDTO dto = new RefundOrderDTO();
        dto.setOrderItemIds(itemIds);
        return dto;
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
        Long passengerId =
                createPassenger(userId, "测试乘车人", idCardNo);
        return new UserPassenger(userId, passengerId);
    }

    private UserPassenger createUserPassengerForUser(Long userId,
                                                     String name,
                                                     String idCardNo) {
        return new UserPassenger(
                userId, createPassenger(userId, name, idCardNo));
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

    private List<Integer> remainingCounts() {
        return jdbcTemplate.queryForList("""
                SELECT remaining_count
                FROM ticket_stock
                WHERE travel_date = ?
                  AND train_id = ?
                  AND seat_type = ?
                ORDER BY segment_index
                """, Integer.class, TEST_DATE, TRAIN_ID, SEAT_TYPE);
    }

    private BigDecimal queryPrice() {
        return jdbcTemplate.queryForObject("""
                SELECT SUM(price)
                FROM train_segment_price
                WHERE train_id = ?
                  AND seat_type = ?
                  AND segment_index BETWEEN 1 AND 4
                """, BigDecimal.class, TRAIN_ID, SEAT_TYPE);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private void cleanup() {
        // 支付流水先删：它引用订单和用户，但表之间没有物理外键。
        jdbcTemplate.update("""
                DELETE FROM pay_log
                WHERE user_id IN (
                    SELECT id FROM sys_user
                    WHERE username IN (?, ?)
                )
                """, PRIMARY_USER, SECOND_USER);
        jdbcTemplate.update("""
                DELETE FROM ticket_order_item
                WHERE order_id IN (
                    SELECT id FROM ticket_order
                    WHERE user_id IN (
                        SELECT id FROM sys_user
                        WHERE username LIKE '%order_test%'
                    )
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM ticket_order
                WHERE user_id IN (
                    SELECT id FROM sys_user
                    WHERE username LIKE '%order_test%'
                )
                """);
        jdbcTemplate.update("""
                DELETE FROM passenger
                WHERE user_id IN (
                    SELECT id FROM sys_user
                    WHERE username LIKE '%order_test%'
                )
                """);
        jdbcTemplate.update(
                "DELETE FROM sys_user WHERE username LIKE '%order_test%'");
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ?
                  AND train_id = ?
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
