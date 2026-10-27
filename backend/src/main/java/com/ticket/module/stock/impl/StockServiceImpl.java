package com.ticket.module.stock.impl;

import com.ticket.common.exception.BizException;
import com.ticket.module.stock.StockService;
import com.ticket.module.stock.TicketStockCache;
import com.ticket.module.stock.dto.StockAdjustDTO;
import com.ticket.module.stock.dto.StockGenerateDTO;
import com.ticket.module.stock.dto.StockQueryDTO;
import com.ticket.module.stock.entity.TicketStock;
import com.ticket.module.stock.mapper.TicketStockMapper;
import com.ticket.module.stock.vo.StockVO;
import com.ticket.module.ticket.TicketCacheInvalidator;
import com.ticket.module.train.entity.Train;
import com.ticket.module.train.mapper.TrainMapper;
import com.ticket.module.train.mapper.TrainSegmentMapper;
import com.ticket.module.train.vo.TrainSegmentVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class StockServiceImpl implements StockService {

    /**
     * 各席别的默认定员（演示用）。
     *
     * <p>真实项目应该按车厢配置算（商务座 1 节、一等座 2 节……），
     * 这里为了简单先写死。
     */
    private static final Map<Integer, Integer> DEFAULT_CAPACITY =
            Map.of(1, 20, 2, 100, 3, 600);

    private final TrainMapper trainMapper;
    private final TrainSegmentMapper segmentMapper;
    private final TicketStockMapper stockMapper;
    private final TicketCacheInvalidator cacheInvalidator;
    private final TicketStockCache stockCache;

    public StockServiceImpl(TrainMapper trainMapper,
                            TrainSegmentMapper segmentMapper,
                            TicketStockMapper stockMapper,
                            TicketCacheInvalidator cacheInvalidator,
                            TicketStockCache stockCache) {
        this.trainMapper = trainMapper;
        this.segmentMapper = segmentMapper;
        this.stockMapper = stockMapper;
        this.cacheInvalidator = cacheInvalidator;
        this.stockCache = stockCache;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int generate(StockGenerateDTO dto) {
        int days = dto.getDays() == null ? 15 : dto.getDays();
        LocalDate start = dto.getStartDate() == null
                ? LocalDate.now().plusDays(1)
                : dto.getStartDate();
        LocalDate endExclusive = start.plusDays(days);

        List<Train> trains = trainMapper.findAllEnabled();
        long beforeCount = stockMapper.countByDateRange(start, endExclusive);

        for (Train train : trains) {
            // 没配停站的车次没有分段，跳过
            List<TrainSegmentVO> segments = segmentMapper.findByTrainId(train.getId());
            if (segments.isEmpty()) {
                continue;
            }

            for (int d = 0; d < days; d++) {
                LocalDate date = start.plusDays(d);
                List<TicketStock> rows = new ArrayList<>();

                for (TrainSegmentVO seg : segments) {
                    for (Map.Entry<Integer, Integer> cap : DEFAULT_CAPACITY.entrySet()) {
                        rows.add(buildStock(train.getId(), date, seg,
                                cap.getKey(), cap.getValue()));
                    }
                }

                stockMapper.batchInsertIgnore(rows);
            }
        }
        /*
         * 不要直接累加 batchInsertIgnore 的返回值：
         * Connector/J 默认按“找到的行”计数，重复键执行
         * ON DUPLICATE KEY UPDATE id = id 时仍可能返回 > 0。
         * 生成前后各数一次，差值是真实新增条数。
         */
        long afterCount = stockMapper.countByDateRange(start, endExclusive);
        cacheInvalidator.invalidateAllAfterCommit();
        return Math.toIntExact(afterCount - beforeCount);
    }

    @Override
    public List<StockVO> list(StockQueryDTO query) {
        return stockMapper.findByTrainAndDate(query.getTrainId(), query.getTravelDate());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjust(Long id, StockAdjustDTO dto) {
        StockVO stock = stockMapper.findById(id);
        if (stock == null) {
            throw new BizException(404, "库存记录不存在");
        }
        if (dto.getRemainingCount() > stock.getTotalCount()) {
            throw new BizException(400,
                    "剩余票数不能大于总票数（" + stock.getTotalCount() + "）");
        }
        stockMapper.updateRemaining(id, dto.getRemainingCount());
        cacheInvalidator.invalidateDateAfterCommit(stock.getTravelDate());
        stockCache.refreshAfterCommit(
                stock.getTravelDate(),
                stock.getTrainId(),
                stock.getSeatType(),
                stock.getSegmentIndex(),
                stock.getSegmentIndex());
    }

    /** 组装一条库存记录 */
    private TicketStock buildStock(Long trainId, LocalDate date, TrainSegmentVO seg,
                                   Integer seatType, Integer capacity) {
        TicketStock s = new TicketStock();
        s.setTravelDate(date);
        s.setTrainId(trainId);
        s.setSegmentIndex(seg.getSegmentIndex());
        s.setSeatType(seatType);
        s.setFromStationId(seg.getFromStationId());
        s.setToStationId(seg.getToStationId());
        s.setTotalCount(capacity);
        s.setSoldCount(0);
        s.setRemainingCount(capacity);
        return s;
    }
}
