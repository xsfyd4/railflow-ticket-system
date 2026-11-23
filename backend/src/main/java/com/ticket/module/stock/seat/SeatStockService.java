package com.ticket.module.stock.seat;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.order.entity.TicketOrder;
import com.ticket.module.stock.entity.TicketStock;
import com.ticket.module.stock.mapper.TicketStockMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SeatStockService {

    private final TicketStockMapper stockMapper;
    private final SeatAvailabilityService availabilityService;

    public SeatStockService(TicketStockMapper stockMapper,
                            SeatAvailabilityService availabilityService) {
        this.stockMapper = stockMapper;
        this.availabilityService = availabilityService;
    }

    public List<TicketStock> lockRange(TicketOrder order) {
        return lockRange(
                order.getTravelDate(),
                order.getTrainId(),
                order.getSeatType(),
                order.getFromSegmentIndex(),
                order.getToSegmentIndex());
    }

    public List<TicketStock> lockRange(java.time.LocalDate travelDate,
                                       Long trainId,
                                       Integer seatType,
                                       int fromSegmentIndex,
                                       int toSegmentIndex) {
        List<TicketStock> rows = stockMapper.findRowsForRangeForUpdate(
                travelDate,
                trainId,
                seatType,
                fromSegmentIndex,
                toSegmentIndex);
        int expected = toSegmentIndex - fromSegmentIndex + 1;
        if (rows.size() != expected) {
            throw new BizException(ResultCode.ORDER_PRICE_CONFIG_ERROR);
        }
        return rows;
    }

    public void occupy(List<TicketStock> stockRows, SeatMask selected) {
        for (TicketStock stock : stockRows) {
            SeatMask sold = SeatMask.fromStock(stock);
            SeatMask occupied = availabilityService.occupy(sold, selected);
            if (occupied.cardinality()
                    != sold.cardinality() + selected.cardinality()) {
                throw new BizException(ResultCode.TICKET_NOT_ENOUGH);
            }
            updateStock(stock, occupied);
        }
    }

    public void release(TicketOrder order, List<Integer> seatIndexes) {
        if (seatIndexes == null || seatIndexes.isEmpty()
                || seatIndexes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
        }
        if (seatIndexes.stream().distinct().count() != seatIndexes.size()) {
            throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
        }
        List<TicketStock> stockRows = lockRange(order);
        int seatCount = stockRows.get(0).getTotalCount();
        SeatMask released = SeatMask.ofSeatIndexes(seatCount, seatIndexes);
        for (TicketStock stock : stockRows) {
            if (!Integer.valueOf(seatCount).equals(stock.getTotalCount())) {
                throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
            }
            SeatMask sold = SeatMask.fromStock(stock);
            SeatMask occupied = availabilityService.release(sold, released);
            if (sold.cardinality() - occupied.cardinality()
                    != released.cardinality()) {
                throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
            }
            updateStock(stock, occupied);
        }
    }

    private void updateStock(TicketStock stock, SeatMask occupied) {
        int soldCount = occupied.cardinality();
        int remainingCount = stock.getTotalCount() - soldCount;
        int updated = stockMapper.updateSeatState(
                stock.getId(),
                soldCount,
                remainingCount,
                occupied.toBytes());
        if (updated != 1) {
            throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
        }
    }
}
