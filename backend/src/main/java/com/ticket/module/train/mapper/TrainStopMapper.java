package com.ticket.module.train.mapper;

import com.ticket.module.train.entity.TrainStop;
import com.ticket.module.train.vo.TrainStopVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** train_stop 表的数据访问层。 */
@Mapper
public interface TrainStopMapper {

    /** 按 stop_order 顺序查某车次的所有停站，带站名 */
    List<TrainStopVO> findByTrainId(@Param("trainId") Long trainId);

    /** 删掉某车次的全部停站（重建前先清空） */
    int deleteByTrainId(@Param("trainId") Long trainId);

    /** 批量插入停站 */
    int batchInsert(@Param("list") List<TrainStop> list);
}
