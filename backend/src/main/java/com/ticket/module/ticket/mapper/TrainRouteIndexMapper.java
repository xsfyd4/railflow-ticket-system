package com.ticket.module.ticket.mapper;

import com.ticket.module.ticket.dto.TrainRouteStopRow;
import com.ticket.module.train.entity.TrainSegmentPrice;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 构建静态车段车次索引的批量读取接口。
 */
@Mapper
public interface TrainRouteIndexMapper {

    List<TrainRouteStopRow> findAllStopsForIndex();

    List<TrainSegmentPrice> findAllPricesForIndex();
}
