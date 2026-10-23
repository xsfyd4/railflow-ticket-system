package com.ticket.module.train.mapper;

import com.ticket.module.train.entity.TrainSegmentPrice;
import com.ticket.module.train.vo.TrainPriceVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** train_segment_price 表的数据访问层。 */
@Mapper
public interface TrainSegmentPriceMapper {

    /** 查某车次的全部票价，按段号、席别排序 */
    List<TrainPriceVO> findByTrainId(@Param("trainId") Long trainId);

    int deleteByTrainId(@Param("trainId") Long trainId);

    int batchInsert(@Param("list") List<TrainSegmentPrice> list);
}
