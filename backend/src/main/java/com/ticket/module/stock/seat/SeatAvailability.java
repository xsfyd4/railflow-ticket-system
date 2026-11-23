package com.ticket.module.stock.seat;

public record SeatAvailability(
        int seatCount,
        int remainingCount,
        SeatMask available
) {
}
