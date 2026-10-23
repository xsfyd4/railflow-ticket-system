package com.ticket.module.train;

import com.ticket.module.train.dto.PricesSaveDTO;
import com.ticket.module.train.dto.StopsSaveDTO;
import com.ticket.module.train.vo.TrainPriceVO;
import com.ticket.module.train.vo.TrainSegmentVO;
import com.ticket.module.train.vo.TrainStopVO;

import java.util.List;

/**
 * 车次的「停站 / 分段 / 票价」业务接口。
 *
 * <p>三者是一套东西：停站决定分段，分段决定票价，
 * 所以放在同一个 Service 里，保证改停站时能一起重建。
 */
public interface TrainStopService {

    /** 查停站列表（按站序） */
    List<TrainStopVO> listStops(Long trainId);

    /** 整体保存停站，并重建分段。这是整个阶段最复杂的接口 */
    void saveStops(Long trainId, StopsSaveDTO dto);

    /** 查分段列表 */
    List<TrainSegmentVO> listSegments(Long trainId);

    /** 查票价列表 */
    List<TrainPriceVO> listPrices(Long trainId);

    /** 整体保存票价（先全删再全插） */
    void savePrices(Long trainId, PricesSaveDTO dto);
}
