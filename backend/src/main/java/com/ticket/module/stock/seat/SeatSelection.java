package com.ticket.module.stock.seat;

import java.util.List;

public record SeatSelection(
        SeatMask selected,
        List<Integer> seatIndexes
) {
}
