package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketSearchSnapshotTest {

    @Test
    void shouldRoundTripSingleObjectWithParallelArrays() {
        TicketVO ticket = new TicketVO();
        ticket.setTrainId(10L);
        ticket.setTrainNo("G1001");
        ticket.setTrainType("G");
        ticket.setFromStationName("北京南");
        ticket.setToStationName("上海虹桥");
        ticket.setDepartTime(LocalTime.of(8, 0));
        ticket.setArriveTime(LocalTime.of(12, 0));
        ticket.setDurationMinutes(240);
        ticket.setMileage(new BigDecimal("1318"));
        ticket.setSeats(List.of(
                new TicketSeatVO(1, 5, new BigDecimal("1748")),
                new TicketSeatVO(2, 20, new BigDecimal("933")),
                new TicketSeatVO(3, 100, new BigDecimal("553"))));

        PageResult<TicketVO> original =
                PageResult.of(1, 1, 10, List.of(ticket));
        TicketSearchSnapshot snapshot =
                TicketSearchSnapshot.from(original);

        assertEquals(1, snapshot.getTrainIds().length);
        assertEquals("G1001", snapshot.getTrainNos()[0]);
        assertEquals(3, snapshot.getRemainCounts()[0].length);
        assertEquals(100, snapshot.getRemainCounts()[0][2]);

        PageResult<TicketVO> restored = snapshot.toPageResult();
        TicketVO value = restored.getRecords().get(0);
        assertEquals(original.getTotal(), restored.getTotal());
        assertEquals("G1001", value.getTrainNo());
        assertEquals(LocalTime.of(8, 0), value.getDepartTime());
        assertEquals(3, value.getSeats().size());
        assertEquals(new BigDecimal("553"),
                value.getSeats().get(2).getPrice());
    }
}
