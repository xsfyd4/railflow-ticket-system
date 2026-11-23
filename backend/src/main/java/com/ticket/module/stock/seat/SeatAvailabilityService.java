package com.ticket.module.stock.seat;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.stock.entity.TicketStock;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SeatAvailabilityService {

    /**
     * rows 必须是同一车次、同一席别、按 segment_index 升序的连续库存行。
     */
    public SeatAvailability calculate(List<TicketStock> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BizException(ResultCode.STOCK_DATA_ERROR);
        }

        int seatCount = rows.get(0).getTotalCount();
        int expectedSegment = rows.get(0).getSegmentIndex();
        SeatMask soldUnion = SeatMask.empty(seatCount);

        for (int i = 0; i < rows.size(); i++) {
            TicketStock row = rows.get(i);
            if (row.getTotalCount() == null
                    || row.getTotalCount() != seatCount
                    || row.getSegmentIndex() == null
                    || row.getSegmentIndex() != expectedSegment + i) {
                throw new BizException(ResultCode.STOCK_DATA_ERROR);
            }
            soldUnion = soldUnion.or(SeatMask.fromStock(row));
        }

        SeatMask available = SeatMask.full(seatCount).andNot(soldUnion);
        return new SeatAvailability(
                seatCount,
                available.cardinality(),
                available);
    }

    public SeatSelection select(SeatAvailability availability, int count) {
        if (count < 0 || availability.remainingCount() < count) {
            throw new BizException(ResultCode.TICKET_NOT_ENOUGH);
        }
        List<Integer> seatIndexes =
                availability.available().firstSeatIndexes(count);
        return new SeatSelection(
                SeatMask.ofSeatIndexes(availability.seatCount(), seatIndexes),
                seatIndexes);
    }

    public SeatMask occupy(SeatMask sold, SeatMask selected) {
        return sold.or(selected);
    }

    public SeatMask release(SeatMask sold, SeatMask released) {
        return sold.andNot(released);
    }
}
