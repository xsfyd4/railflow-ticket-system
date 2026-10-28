package com.ticket.module.ticket.impl;

import com.ticket.common.result.PageResult;
import com.ticket.module.stock.entity.TicketStock;
import com.ticket.module.stock.mapper.TicketStockMapper;
import com.ticket.module.ticket.RouteBucket;
import com.ticket.module.ticket.TrainRouteIndex;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于本地车段车次索引和一次库存 SQL 的快查询。
 */
@Service
public class IndexedTicketSearchService {

    private final TrainRouteIndex routeIndex;
    private final TicketStockMapper stockMapper;

    public IndexedTicketSearchService(TrainRouteIndex routeIndex,
                                      TicketStockMapper stockMapper) {
        this.routeIndex = routeIndex;
        this.stockMapper = stockMapper;
    }

    public PageResult<TicketVO> search(TicketSearchDTO query) {
        RouteBucket bucket = routeIndex.lookup(
                query.getFromStationId(), query.getToStationId());
        if (bucket == null || bucket.size() == 0) {
            return PageResult.of(
                    0, query.getPage(), query.getSize(), List.of());
        }

        List<Long> trainIds = new ArrayList<>(bucket.size());
        for (long trainId : bucket.getTrainIds()) {
            trainIds.add(trainId);
        }
        List<TicketStock> stockRows = stockMapper.findForSearch(
                query.getTravelDate(), trainIds);
        Map<Long, Map<Integer, Map<Integer, Integer>>> stock =
                indexStock(stockRows);

        List<TicketVO> allSellable = new ArrayList<>();
        for (int i = 0; i < bucket.size(); i++) {
            TicketVO ticket = toTicket(bucket, i, stock, query);
            if (ticket != null) {
                allSellable.add(ticket);
            }
        }

        int page = query.getPage();
        int size = query.getSize();
        int from = Math.min((page - 1) * size, allSellable.size());
        int to = Math.min(from + size, allSellable.size());
        return PageResult.of(
                allSellable.size(), page, size,
                allSellable.subList(from, to));
    }

    private Map<Long, Map<Integer, Map<Integer, Integer>>> indexStock(
            List<TicketStock> rows) {
        Map<Long, Map<Integer, Map<Integer, Integer>>> result =
                new HashMap<>();
        for (TicketStock row : rows) {
            result
                    .computeIfAbsent(row.getTrainId(),
                            ignored -> new HashMap<>())
                    .computeIfAbsent(row.getSeatType(),
                            ignored -> new HashMap<>())
                    .put(row.getSegmentIndex(),
                            row.getRemainingCount());
        }
        return result;
    }

    private TicketVO toTicket(
            RouteBucket bucket,
            int index,
            Map<Long, Map<Integer, Map<Integer, Integer>>> stock,
            TicketSearchDTO query) {
        long trainId = bucket.getTrainIds()[index];
        int fromSegment = bucket.getFromSegmentIndexes()[index];
        int toSegment = bucket.getToSegmentIndexes()[index];
        List<TicketSeatVO> seats = new ArrayList<>(3);
        int firstSeat = query.getSeatType() == null
                ? 1 : query.getSeatType();
        int lastSeat = query.getSeatType() == null
                ? 3 : query.getSeatType();
        for (int seatType = firstSeat; seatType <= lastSeat; seatType++) {
            BigDecimal price = bucket.getPrices()[index][seatType - 1];
            if (price == null) {
                continue;
            }
            Map<Integer, Integer> remains = stock
                    .getOrDefault(trainId, Map.of())
                    .getOrDefault(seatType, Map.of());
            Integer remain = minimumRemaining(
                    remains, fromSegment, toSegment);
            if (remain != null) {
                seats.add(new TicketSeatVO(seatType, remain, price));
            }
        }
        if (seats.isEmpty()) {
            return null;
        }

        TicketVO ticket = new TicketVO();
        ticket.setTrainId(trainId);
        ticket.setTrainNo(bucket.getTrainNos()[index]);
        ticket.setTrainType(bucket.getTrainTypes()[index]);
        ticket.setFromStationName(
                bucket.getFromStationNames()[index]);
        ticket.setToStationName(
                bucket.getToStationNames()[index]);
        ticket.setDepartTime(bucket.getDepartTimes()[index]);
        ticket.setArriveTime(bucket.getArriveTimes()[index]);
        ticket.setDurationMinutes(
                bucket.getDurationMinutes()[index]);
        ticket.setMileage(bucket.getMileages()[index]);
        ticket.setSeats(seats);
        return ticket;
    }

    private Integer minimumRemaining(Map<Integer, Integer> remains,
                                     int fromSegment,
                                     int toSegment) {
        Integer minimum = null;
        for (int segment = fromSegment;
             segment <= toSegment;
             segment++) {
            Integer remain = remains.get(segment);
            if (remain == null) {
                return null;
            }
            minimum = minimum == null
                    ? remain : Math.min(minimum, remain);
        }
        return minimum;
    }
}
