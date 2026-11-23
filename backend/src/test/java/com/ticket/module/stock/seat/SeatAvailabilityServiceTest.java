package com.ticket.module.stock.seat;

import com.ticket.common.exception.BizException;
import com.ticket.module.stock.entity.TicketStock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeatAvailabilityServiceTest {

    private final SeatAvailabilityService service =
            new SeatAvailabilityService();

    @Test
    void minimumPerSegmentCanOverestimateContinuousTickets() {
        TicketStock first = stock(1, 3, 1, List.of(1, 2));
        TicketStock second = stock(2, 3, 1, List.of(2, 3));

        SeatAvailability availability =
                service.calculate(List.of(first, second));

        assertEquals(0, availability.remainingCount());
        assertThrows(BizException.class,
                () -> service.select(availability, 1));
    }

    @Test
    void continuousAvailableSeatIsCountedAndSelected() {
        TicketStock first = stock(1, 5, 2, List.of(1, 3, 4));
        TicketStock second = stock(2, 5, 3, List.of(2, 3));

        SeatAvailability availability =
                service.calculate(List.of(first, second));

        assertEquals(1, availability.remainingCount());
        SeatSelection selection = service.select(availability, 1);
        assertEquals(List.of(5), selection.seatIndexes());
    }

    @Test
    void rejectsMissingSegmentRows() {
        TicketStock first = stock(1, 5, 0, List.of());
        TicketStock third = stock(3, 5, 0, List.of());

        assertThrows(BizException.class,
                () -> service.calculate(List.of(first, third)));
    }

    private TicketStock stock(int segment,
                              int total,
                              int remaining,
                              List<Integer> soldSeats) {
        TicketStock stock = new TicketStock();
        stock.setSegmentIndex(segment);
        stock.setTotalCount(total);
        stock.setSoldCount(soldSeats.size());
        stock.setRemainingCount(remaining);
        stock.setSoldSeatMask(
                SeatMask.ofSeatIndexes(total, soldSeats).toBytes());
        return stock;
    }
}
