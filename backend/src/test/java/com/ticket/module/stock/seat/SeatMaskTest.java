package com.ticket.module.stock.seat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeatMaskTest {

    @Test
    void serializesAndDeserializesSeatsBeyondFirstWord() {
        SeatMask mask = SeatMask.ofSeatIndexes(
                600, List.of(1, 64, 65, 128, 599, 600));

        byte[] bytes = mask.toBytes();
        SeatMask restored = SeatMask.fromBytes(bytes, 600);

        assertEquals(6, restored.cardinality());
        assertEquals(
                List.of(1, 64, 65, 128, 599, 600),
                restored.firstSeatIndexes(6));
    }

    @Test
    void legacyCountsBecomeDeterministicSeatMask() {
        var stock = new com.ticket.module.stock.entity.TicketStock();
        stock.setTotalCount(10);
        stock.setSoldCount(3);
        stock.setRemainingCount(8);

        SeatMask mask = SeatMask.fromStock(stock);

        assertEquals(3, mask.cardinality());
        assertEquals(List.of(1, 2, 3), mask.firstSeatIndexes(3));
    }
}
