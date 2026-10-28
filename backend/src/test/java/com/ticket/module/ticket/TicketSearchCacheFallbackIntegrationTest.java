package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Redis 指向不可用端口时，查票仍必须降级到 MySQL。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ticket.redis.cache.port=6396",
                "ticket.redis.cache.replica-nodes=localhost:6397,localhost:6398",
                "ticket.redis.command-timeout-ms=300",
                "ticket.redis.reconcile.enabled=false"
        }
)
@Transactional
class TicketSearchCacheFallbackIntegrationTest {

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldFallbackToMysqlWhenRedisIsUnavailable() {
        LocalDate testDate = LocalDate.of(2099, 8, 1);
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
                """, testDate);

        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(testDate);
        query.setSeatType(3);

        PageResult<TicketVO> result = ticketService.search(query);

        assertFalse(result.getRecords().isEmpty(),
                "Redis 不可用时仍应返回 MySQL 查票结果");
    }
}
