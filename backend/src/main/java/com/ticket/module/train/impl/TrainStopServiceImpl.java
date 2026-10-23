package com.ticket.module.train.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.train.TrainStopService;
import com.ticket.module.train.dto.PricesSaveDTO;
import com.ticket.module.train.dto.StopsSaveDTO;
import com.ticket.module.train.dto.TrainPriceItemDTO;
import com.ticket.module.train.dto.TrainStopItemDTO;
import com.ticket.module.train.entity.TrainSegment;
import com.ticket.module.train.entity.TrainSegmentPrice;
import com.ticket.module.train.entity.TrainStop;
import com.ticket.module.train.mapper.TrainMapper;
import com.ticket.module.train.mapper.TrainSegmentMapper;
import com.ticket.module.train.mapper.TrainSegmentPriceMapper;
import com.ticket.module.train.mapper.TrainStopMapper;
import com.ticket.module.train.vo.TrainPriceVO;
import com.ticket.module.train.vo.TrainSegmentVO;
import com.ticket.module.train.vo.TrainStopVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 停站 / 分段 / 票价的实现。
 *
 * <p>核心规则：停站决定分段，分段决定票价，三者必须在同一个事务里维护。
 */
@Service
public class TrainStopServiceImpl implements TrainStopService {

    private final TrainMapper trainMapper;
    private final TrainStopMapper stopMapper;
    private final TrainSegmentMapper segmentMapper;
    private final TrainSegmentPriceMapper priceMapper;

    public TrainStopServiceImpl(TrainMapper trainMapper,
                                TrainStopMapper stopMapper,
                                TrainSegmentMapper segmentMapper,
                                TrainSegmentPriceMapper priceMapper) {
        this.trainMapper = trainMapper;
        this.stopMapper = stopMapper;
        this.segmentMapper = segmentMapper;
        this.priceMapper = priceMapper;
    }

    @Override
    public List<TrainStopVO> listStops(Long trainId) {
        ensureTrainExists(trainId);
        return stopMapper.findByTrainId(trainId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveStops(Long trainId, StopsSaveDTO dto) {

        // 1. 车次必须存在
        ensureTrainExists(trainId);

        List<TrainStopItemDTO> stops = dto.getStops();

        // 2. 校验入参
        if (stops == null || stops.size() < 2) {
            throw new BizException(400, "至少要有始发站和终到站两站");
        }
        Set<Long> stationIds = new HashSet<>();
        for (TrainStopItemDTO s : stops) {
            if (!stationIds.add(s.getStationId())) {
                throw new BizException(400, "同一个车次不能重复停靠同一个车站");
            }
        }
        if (stops.get(0).getDepartureTime() == null) {
            throw new BizException(400, "始发站必须有发车时间");
        }
        if (stops.get(stops.size() - 1).getArrivalTime() == null) {
            throw new BizException(400, "终到站必须有到达时间");
        }

        // 3. 有库存就禁止改停站（改了 segment_index，库存就对不上了）
        if (trainMapper.countStocks(trainId) > 0) {
            throw new BizException(400, "该车次已生成库存，请先清理库存后再修改停站");
        }

        // 4. 删旧数据（票 → 段 → 站）
        priceMapper.deleteByTrainId(trainId);
        segmentMapper.deleteByTrainId(trainId);
        stopMapper.deleteByTrainId(trainId);

        // 5. 按数组顺序插入新停站
        List<TrainStop> stopEntities = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            stopEntities.add(toStopEntity(stops.get(i), trainId, i, stops.size()));
        }
        stopMapper.batchInsert(stopEntities);

        // 6. 按相邻两站重建分段
        List<TrainSegment> segments = new ArrayList<>();
        for (int i = 1; i < stops.size(); i++) {
            segments.add(toSegment(stops.get(i - 1), stops.get(i), trainId, i));
        }
        segmentMapper.batchInsert(segments);

        // 7. 同步 train 的首尾站和总里程
        TrainStopItemDTO first = stops.get(0);
        TrainStopItemDTO last = stops.get(stops.size() - 1);
        BigDecimal totalMileage = last.getMileage() == null ? BigDecimal.ZERO : last.getMileage();
        trainMapper.updateStations(trainId, first.getStationId(), last.getStationId(), totalMileage);
    }

    @Override
    public List<TrainSegmentVO> listSegments(Long trainId) {
        ensureTrainExists(trainId);
        return segmentMapper.findByTrainId(trainId);
    }

    @Override
    public List<TrainPriceVO> listPrices(Long trainId) {
        ensureTrainExists(trainId);
        return priceMapper.findByTrainId(trainId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void savePrices(Long trainId, PricesSaveDTO dto) {

        // 1. 车次存在
        ensureTrainExists(trainId);

        // 2. 必须先有分段
        int segmentCount = segmentMapper.findByTrainId(trainId).size();
        if (segmentCount == 0) {
            throw new BizException(400, "该车次还没有分段，请先配置停站");
        }

        // 3. 校验段号范围和重复（同一段 + 同一席别只能一条）
        Set<String> seen = new HashSet<>();
        for (TrainPriceItemDTO item : dto.getPrices()) {
            Integer idx = item.getSegmentIndex();
            if (idx == null || idx < 1 || idx > segmentCount) {
                throw new BizException(400, "段号超出范围：" + idx + "（当前共 " + segmentCount + " 段）");
            }
            String key = idx + "-" + item.getSeatType();
            if (!seen.add(key)) {
                throw new BizException(400,
                        "第 " + idx + " 段的席别 " + item.getSeatType() + " 重复配置");
            }
        }

        // 4. 先删后插（否则会撞 uk_price 唯一键）
        priceMapper.deleteByTrainId(trainId);

        List<TrainSegmentPrice> prices = new ArrayList<>();
        for (TrainPriceItemDTO item : dto.getPrices()) {
            TrainSegmentPrice p = new TrainSegmentPrice();
            p.setTrainId(trainId);
            p.setSegmentIndex(item.getSegmentIndex());
            p.setSeatType(item.getSeatType());
            p.setPrice(item.getPrice());
            prices.add(p);
        }
        priceMapper.batchInsert(prices);
    }

    // ==================== 私有工具方法 ====================

    /** 车次不存在就抛 2004 */
    private void ensureTrainExists(Long trainId) {
        if (trainMapper.findById(trainId) == null) {
            throw new BizException(ResultCode.TRAIN_NOT_FOUND);
        }
    }

    /**
     * 一站 DTO → 一站实体。
     *
     * <p>stopOrder / isStart / isEnd 都是算出来的，DTO 里没有。
     */
    private TrainStop toStopEntity(TrainStopItemDTO d, Long trainId, int index, int total) {
        TrainStop e = new TrainStop();
        e.setTrainId(trainId);
        e.setStopOrder(index + 1);
        e.setStationId(d.getStationId());
        e.setArrivalTime(d.getArrivalTime());
        e.setDepartureTime(d.getDepartureTime());
        e.setDayOffset(d.getDayOffset() == null ? 0 : d.getDayOffset());
        e.setMileage(d.getMileage() == null ? BigDecimal.ZERO : d.getMileage());
        e.setIsStart(index == 0 ? 1 : 0);
        e.setIsEnd(index == total - 1 ? 1 : 0);
        return e;
    }

    /**
     * 相邻两站 → 一段。
     *
     * <p>第 index 段 = 第 index 站 → 第 index+1 站；
     * 本段里程 = 到达站累计里程 - 出发站累计里程。
     */
    private TrainSegment toSegment(TrainStopItemDTO from, TrainStopItemDTO to,
                                   Long trainId, int index) {
        BigDecimal fromMile = from.getMileage() == null ? BigDecimal.ZERO : from.getMileage();
        BigDecimal toMile = to.getMileage() == null ? BigDecimal.ZERO : to.getMileage();

        TrainSegment g = new TrainSegment();
        g.setTrainId(trainId);
        g.setSegmentIndex(index);
        g.setFromStopOrder(index);
        g.setToStopOrder(index + 1);
        g.setFromStationId(from.getStationId());
        g.setToStationId(to.getStationId());
        g.setMileage(toMile.subtract(fromMile));
        return g;
    }
}
