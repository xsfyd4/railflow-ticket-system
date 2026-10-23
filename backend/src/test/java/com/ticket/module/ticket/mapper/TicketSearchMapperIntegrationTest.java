package com.ticket.module.ticket.mapper;

import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketSeatRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只验证查票 SQL 和 XML 映射，不启动 Tomcat。
 *
 * <p>测试使用 2099 年的临时库存，事务结束后自动回滚，不污染演示数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class TicketSearchMapperIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 1, 1);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TicketSearchMapper mapper;

    @Test
    void shouldReturnMinRemainingAndSummedPriceAcrossSegments() {
        prepareStock();

        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(TEST_DATE);
        query.setPage(1);
        query.setSize(10);

        assertEquals(1L, mapper.countTrains(query));

        List<Long> trainIds = mapper.findTrainIds(query, 0);
        assertEquals(List.of(1L), trainIds);

        query.setSeatType(2);
        List<TicketSeatRow> rows = mapper.findSeatRows(query, trainIds);
        assertEquals(1, rows.size());

        TicketSeatRow row = rows.get(0);
        assertEquals("G1001", row.getTrainNo());
        assertEquals(7, row.getRemainCount(),
                "G1001 第 3 段只剩 7 张，所以北京南到上海虹桥也只能卖 7 张");
        assertEquals(301, row.getDurationMinutes());
        assertTrue(row.getPrice().signum() > 0);
    }

    private void prepareStock() {
        jdbcTemplate.update("""
                INSERT INTO ticket_stock
                    (travel_date, train_id, segment_index, seat_type,
                     from_station_id, to_station_id,
                     total_count, sold_count, remaining_count)
                SELECT ?, seg.train_id, seg.segment_index, cap.seat_type,
                       seg.from_station_id, seg.to_station_id,
                       cap.total_count, 0, cap.total_count
                FROM train_segment seg
                CROSS JOIN (
                    SELECT 1 AS seat_type, 20 AS total_count
                    UNION ALL SELECT 2, 100
                    UNION ALL SELECT 3, 600
                ) cap
                WHERE seg.train_id = 1
                """, TEST_DATE);

        jdbcTemplate.update("""
                UPDATE ticket_stock
                SET remaining_count = 7
                WHERE travel_date = ?
                  AND train_id = 1
                  AND segment_index = 3
                  AND seat_type = 2
                """, TEST_DATE);
    }
}
