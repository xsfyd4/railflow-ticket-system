package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.impl.IndexedTicketSearchService;
import com.ticket.module.ticket.vo.TicketVO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TrainRouteIndexSearchIntegrationTest {

    @Autowired
    private TrainRouteIndex index;

    @Autowired
    private IndexedTicketSearchService indexedSearch;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketSearchCache cache;

    @Autowired
    private TicketCacheInvalidator cacheInvalidator;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void shouldBuildRouteIndexAndServeSearchFromIndex() throws Exception {
        awaitReady(index);
        assertTrue(index.routeCount() > 0);
        assertTrue(index.trainRelationCount() >= index.routeCount());
        assertNotNull(index.lookup(1L, 27L));

        TicketSearchDTO query = query();
        PageResult<TicketVO> direct = indexedSearch.search(query);
        assertFalse(direct.getRecords().isEmpty());
        assertTrue(direct.getRecords().stream()
                .anyMatch(ticket -> "G1001".equals(ticket.getTrainNo())));

        cache.invalidateAll();
        Counter counter = meterRegistry.counter(
                "ticket.search.path.index");
        double before = counter.count();
        PageResult<TicketVO> result = ticketService.search(query());
        assertEqualsOneMore(before, counter.count());
        assertFalse(result.getRecords().isEmpty());

        long oldVersion = index.version();
        cacheInvalidator.invalidateStaticDataAfterCommit();
        awaitVersion(index, oldVersion);
        assertTrue(index.version() > oldVersion);
    }

    private TicketSearchDTO query() {
        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(LocalDate.of(2026, 10, 30));
        query.setSeatType(3);
        query.setPage(1);
        query.setSize(10);
        return query;
    }

    private void awaitReady(TrainRouteIndex routeIndex)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (routeIndex.isReady()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(routeIndex.isReady(), "索引未在 5 秒内完成构建");
    }

    private void assertEqualsOneMore(double before, double after) {
        assertTrue(after >= before + 1,
                "查票未命中后应走索引快查询，before="
                        + before + ", after=" + after);
    }

    private void awaitVersion(TrainRouteIndex routeIndex,
                              long oldVersion)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (routeIndex.isReady()
                    && routeIndex.version() > oldVersion) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(routeIndex.version() > oldVersion,
                "静态数据变更后索引版本未更新");
    }
}
