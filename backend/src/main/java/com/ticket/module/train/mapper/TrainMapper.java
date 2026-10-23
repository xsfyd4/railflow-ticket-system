package com.ticket.module.train.mapper;

import com.ticket.module.train.dto.TrainQueryDTO;
import com.ticket.module.train.entity.Train;
import com.ticket.module.train.vo.TrainVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * train 表的数据访问层。
 *
 * <p>这里有一个和之前不同的地方：**查询方法返回的是 TrainVO，不是实体**。
 *
 * <p>为什么？因为 SQL 里 JOIN 了 station，多出 start_station_name /
 * end_station_name 两个字段，Train 实体里没有地方放。
 * MyBatis 的 resultType 可以直接映射到任意类，所以让 VO 兼当"查询结果对象"。
 *
 * <p>这不违反分层——**"查询结果"本来就是一种数据传输对象**。
 * 只是要清楚：写入（insert / update）用的仍然是实体。
 */
@Mapper
public interface TrainMapper {

    /** 分页查询，带站名（JOIN station） */
    List<TrainVO> findPage(@Param("query") TrainQueryDTO query,
                           @Param("offset") int offset);

    /** 总数，不需要 JOIN */
    long countByQuery(@Param("query") TrainQueryDTO query);

    /** 按 id 查详情，带站名 */
    TrainVO findById(@Param("id") Long id);

    /** 按车次号查，用于新增时的唯一性校验 */
    Train findByTrainNo(@Param("trainNo") String trainNo);

    /** 新增，主键写回 train.id */
    int insert(Train train);

    /** 修改 */
    int update(Train train);

    // ================= 删除车次时的级联清理 =================
    // 一个车次被删，它的停站、分段、票价、库存都成了孤儿数据，
    // 必须一起删掉。这五个方法要在同一个事务里执行。

    int deleteById(@Param("id") Long id);

    int deleteStopsByTrainId(@Param("trainId") Long trainId);

    int deleteSegmentsByTrainId(@Param("trainId") Long trainId);

    int deletePricesByTrainId(@Param("trainId") Long trainId);

    int deleteStocksByTrainId(@Param("trainId") Long trainId);

    /**
     * 这个车次有没有被订单引用。
     *
     * <p>有订单的话不能删——删了历史订单就指向一个不存在的车次了。
     * 阶段 5 之前 ticket_order 是空表，这个方法永远返回 0。
     */
    long countOrders(@Param("trainId") Long trainId);

    /**
     * 这个车次生成过库存没有（任意日期任意段都算）。
     *
     * <p>用来判断「能不能改停站」：一旦有库存，segment_index 就和库存绑定了，
     * 再改站序会让库存对不上分段。所以有库存时禁止修改停站。
     */
    long countStocks(@Param("trainId") Long trainId);

    /**
     * 只更新首尾站和总里程，其他字段不动。
     *
     * <p>为什么不用 update(train)？因为那是全量覆盖，
     * 只 set 三个字段的话，train_no、train_type、status、remark 会被写成 null。
     */
    int updateStations(@Param("id") Long id,
                       @Param("startStationId") Long startStationId,
                       @Param("endStationId") Long endStationId,
                       @Param("totalMileage") java.math.BigDecimal totalMileage);
}
