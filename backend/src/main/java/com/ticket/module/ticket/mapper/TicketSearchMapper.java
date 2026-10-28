package com.ticket.module.ticket.mapper;

import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketSeatRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 查票用的查询接口（不是单表 CRUD，是跨 6 张表的聚合查询）。
 *
 * <p>为什么拆成三个方法？因为"一个车次 3 个席别"会让 JOIN 放大行数，
 * 直接 LIMIT 分页会把车次切碎。所以：
 * ① countTrains 数车次
 * ② findTrainIds 只取当页的车次 id（分页在这里）
 * ③ findSeatRows 再按 id 取这些车次的席别余票
 */
@Mapper
public interface TicketSearchMapper {

    /** 符合条件的车次数（去重） */
    long countTrains(@Param("q") TicketSearchDTO q);

    /** 当页的车次 id，按发车时间排序 */
    List<Long> findTrainIds(@Param("q") TicketSearchDTO q,
                            @Param("offset") int offset);

    /** 这些车次的"每席别"余票和票价（已按区间取 MIN / SUM） */
    List<TicketSeatRow> findSeatRows(@Param("q") TicketSearchDTO q,
                                     @Param("trainIds") List<Long> trainIds);

    /**
     * 从停站关系抽取有方向的站对，作为查票对象预热的候选线路。
     */
    List<TicketSearchDTO> findWarmupStationPairs(
            @Param("limit") int limit);
}
