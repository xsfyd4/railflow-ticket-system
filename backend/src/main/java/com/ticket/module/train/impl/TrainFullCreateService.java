package com.ticket.module.train.impl;

import com.ticket.common.exception.BizException;
import com.ticket.module.train.TrainService;
import com.ticket.module.train.TrainStopService;
import com.ticket.module.train.dto.PricesSaveDTO;
import com.ticket.module.train.dto.StopsSaveDTO;
import com.ticket.module.train.dto.TrainFullCreateDTO;
import com.ticket.module.train.dto.TrainPriceItemDTO;
import com.ticket.module.train.dto.TrainSaveDTO;
import com.ticket.module.train.dto.TrainStopItemDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 完整创建车次：基础信息、停站、分段和票价必须全部成功。
 */
@Service
public class TrainFullCreateService {

    private static final Set<Integer> REQUIRED_SEAT_TYPES = Set.of(1, 2, 3);

    private final TrainService trainService;
    private final TrainStopService trainStopService;

    public TrainFullCreateService(TrainService trainService,
                                  TrainStopService trainStopService) {
        this.trainService = trainService;
        this.trainStopService = trainStopService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(TrainFullCreateDTO dto) {
        List<TrainStopItemDTO> stops = dto.getStops();
        validateStops(stops);
        validatePrices(dto.getPrices(), stops.size() - 1);

        TrainStopItemDTO first = stops.get(0);
        TrainStopItemDTO last = stops.get(stops.size() - 1);

        TrainSaveDTO base = new TrainSaveDTO();
        base.setTrainNo(dto.getTrainNo());
        base.setTrainType(dto.getTrainType());
        base.setStartStationId(first.getStationId());
        base.setEndStationId(last.getStationId());
        base.setTotalMileage(last.getMileage() == null
                ? BigDecimal.ZERO : last.getMileage());
        base.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        base.setRemark(dto.getRemark());

        Long trainId = trainService.create(base);

        StopsSaveDTO stopsDto = new StopsSaveDTO();
        stopsDto.setStops(stops);
        trainStopService.saveStops(trainId, stopsDto);

        PricesSaveDTO pricesDto = new PricesSaveDTO();
        pricesDto.setPrices(dto.getPrices());
        trainStopService.savePrices(trainId, pricesDto);
        return trainId;
    }

    private void validateStops(List<TrainStopItemDTO> stops) {
        Set<Long> stationIds = new HashSet<>();
        for (int i = 0; i < stops.size(); i++) {
            TrainStopItemDTO stop = stops.get(i);
            if (!stationIds.add(stop.getStationId())) {
                throw new BizException(400, "同一个车次不能重复停靠同一个车站");
            }
            if (i == 0 && stop.getDepartureTime() == null) {
                throw new BizException(400, "始发站必须有发车时间");
            }
            if (i == stops.size() - 1 && stop.getArrivalTime() == null) {
                throw new BizException(400, "终到站必须有到达时间");
            }
        }
    }

    private void validatePrices(List<TrainPriceItemDTO> prices,
                                int segmentCount) {
        Set<String> configured = new HashSet<>();
        for (TrainPriceItemDTO price : prices) {
            if (price.getSegmentIndex() == null
                    || price.getSegmentIndex() < 1
                    || price.getSegmentIndex() > segmentCount) {
                throw new BizException(400,
                        "票价段号超出范围：" + price.getSegmentIndex());
            }
            if (!REQUIRED_SEAT_TYPES.contains(price.getSeatType())) {
                throw new BizException(400, "席别只能是 1、2、3");
            }
            configured.add(price.getSegmentIndex() + "-" + price.getSeatType());
        }

        int expected = segmentCount * REQUIRED_SEAT_TYPES.size();
        if (configured.size() != expected) {
            throw new BizException(400,
                    "每一段都必须配置商务座、一等座和二等座票价");
        }
    }
}
