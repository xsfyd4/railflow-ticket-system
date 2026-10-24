package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.stock.StockService;
import com.ticket.module.stock.dto.StockAdjustDTO;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TicketSearchCacheIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 5, 1);

    @Autowired
    private TicketService ticketService;

    @Autowired
    private StockService stockService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        Set<String> keys = redis.keys("ticket:search:*" + TEST_DATE + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
