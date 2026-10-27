package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.order.OrderService;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.dto.RefundOrderDTO;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.module.order.vo.OrderDetailVO;
import com.ticket.module.stock.TicketStockCache;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TicketCacheOrderInvalidationIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 6, 1);
    private static final String USERNAME = "__cache_order_user__";

    @Autowired
    private TicketService ticketService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TicketStockCache stockCache;

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
                VALUES (?, '缓存测试乘客', '110101199001019999', 1)
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
                SELECT ?, seg.train_id, seg.segment_index, 3,
                       seg.from_station_id, seg.to_station_id,
                       10, 0, 10
                FROM train_segment seg
                WHERE seg.train_id = 1
                """, TEST_DATE);
        LoginUserContext.set(new LoginUser(userId, USERNAME, "USER"));
    }

    @AfterEach
    void tearDown() {
        LoginUserContext.clear();
        cleanup();
    }

    @Test
    void shouldInvalidateAfterCreateCancelAndRefund() {
        assertEquals(10, searchRemain());

        CreateOrderVO firstOrder = orderService.create(orderDto());
        assertEquals(9, searchRemain(), "下单提交后应立即失效");

        orderService.cancel(firstOrder.getOrderNo());
        assertEquals(10, searchRemain(), "取消提交后应立即失效");

        CreateOrderVO secondOrder = orderService.create(orderDto());
        assertEquals(9, searchRemain());
        jdbcTemplate.update("""
                UPDATE ticket_order SET status = 1, paid_at = NOW()
                WHERE id = ?
                """, secondOrder.getOrderId());

        OrderDetailVO detail = orderService.detail(secondOrder.getOrderNo());
        RefundOrderDTO refund = new RefundOrderDTO();
        refund.setOrderItemIds(List.of(detail.getItems().get(0).getId()));
        orderService.refund(secondOrder.getOrderNo(), refund);
        assertEquals(10, searchRemain(), "退票提交后应立即失效");
    }

    private CreateOrderDTO orderDto() {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setTrainId(1L);
        dto.setTravelDate(TEST_DATE);
        dto.setFromStationId(1L);
        dto.setToStationId(27L);
        dto.setSeatType(3);
        dto.setPassengerIds(List.of(passengerId));
        return dto;
    }

    private int searchRemain() {
        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(TEST_DATE);
        query.setSeatType(3);
        PageResult<TicketVO> page = ticketService.search(query);
        TicketVO train = page.getRecords().stream()
                .filter(item -> "G1001".equals(item.getTrainNo()))
                .findFirst()
                .orElseThrow();
        return train.getSeats().stream()
                .filter(item -> item.getSeatType() == 3)
                .map(TicketSeatVO::getRemainCount)
                .findFirst()
                .orElseThrow();
    }

    private void cleanup() {
        Set<String> cacheKeys = redis.keys("ticket:search:*" + TEST_DATE + "*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redis.delete(cacheKeys);
        }

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
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                DELETE FROM ticket_stock
                WHERE travel_date = ? AND train_id = 1
                """, TEST_DATE);
        List<String> stockKeys = new java.util.ArrayList<>();
        for (int segment = 1; segment <= 4; segment++) {
            stockKeys.add(stockCache.key(TEST_DATE, 1L, 3, segment));
        }
        redis.delete(stockKeys);
    }
}
