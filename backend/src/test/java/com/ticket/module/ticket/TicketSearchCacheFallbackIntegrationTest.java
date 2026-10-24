package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Redis 指向不可用端口时，查票仍必须降级到 MySQL。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.data.redis.port=6390",
                "spring.data.redis.timeout=300ms"
        }
)
class TicketSearchCacheFallbackIntegrationTest {

    @Autowired
    private TicketService ticketService;

    @Test
    void shouldFallbackToMysqlWhenRedisIsUnavailable() {
        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(LocalDate.of(2026, 10, 24));
        query.setSeatType(3);

        PageResult<TicketVO> result = ticketService.search(query);

        assertFalse(result.getRecords().isEmpty(),
                "Redis 不可用时仍应返回 MySQL 查票结果");
    }
}
