package com.ticket.module.stock.impl;

import com.ticket.common.exception.BizException;
import com.ticket.module.stock.StockService;
import com.ticket.module.stock.dto.StockAdjustDTO;
import com.ticket.module.stock.dto.StockGenerateDTO;
import com.ticket.module.stock.dto.StockQueryDTO;
import com.ticket.module.stock.vo.StockVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class StockServiceImplIntegrationTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2099, 1, 2);

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldGenerateIdempotentlyAndAdjustRemainingCount() {
        Integer expected = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) * 3
                FROM train_segment seg
                INNER JOIN train t ON t.id = seg.train_id
                WHERE t.status = 1
                """, Integer.class);

        StockGenerateDTO generate = new StockGenerateDTO();
        generate.setStartDate(TEST_DATE);
        generate.setDays(1);

        assertEquals(expected, stockService.generate(generate));
        int beforeSecondRun = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_stock WHERE travel_date = ?",
                Integer.class, TEST_DATE);
        assertEquals(0, stockService.generate(generate),
                "重复生成不能新增，也不能覆盖已有库存");
        int afterSecondRun = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_stock WHERE travel_date = ?",
                Integer.class, TEST_DATE);
        assertEquals(beforeSecondRun, afterSecondRun,
                "幂等生成后库存行数必须保持不变");

        StockQueryDTO query = new StockQueryDTO();
        query.setTrainId(1L);
        query.setTravelDate(TEST_DATE);
        List<StockVO> stocks = stockService.list(query);
        assertEquals(12, stocks.size(), "G1001 是 4 段 × 3 席别");

        StockVO first = stocks.get(0);
        StockAdjustDTO tooLarge = new StockAdjustDTO();
        tooLarge.setRemainingCount(first.getTotalCount() + 1);
        BizException error = assertThrows(BizException.class,
                () -> stockService.adjust(first.getId(), tooLarge));
        assertEquals(400, error.getCode());

        StockAdjustDTO adjust = new StockAdjustDTO();
        adjust.setRemainingCount(5);
        stockService.adjust(first.getId(), adjust);

        StockVO updated = stockService.list(query).get(0);
        assertEquals(5, updated.getRemainingCount());
        assertEquals(updated.getTotalCount() - 5, updated.getSoldCount(),
                "remaining 和 sold 必须始终保持 remaining = total - sold");
    }
}
