package com.ticket.module.train.mapper;

import com.ticket.module.train.entity.TrainSegment;
import com.ticket.module.train.vo.TrainSegmentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** train_segment 表的数据访问层。 */
@Mapper
public interface TrainSegmentMapper {

    /** 按 segment_index 顺序查某车次的所有段，带起止站名 */
    List<TrainSegmentVO> findByTrainId(@Param("trainId") Long trainId);

    int deleteByTrainId(@Param("trainId") Long trainId);

    int batchInsert(@Param("list") List<TrainSegment> list);
}
