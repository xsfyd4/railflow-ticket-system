package com.ticket.module.stock.mapper;

import com.ticket.module.stock.entity.TicketStock;
import com.ticket.module.stock.vo.StockVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** ticket_stock 表的数据访问层。 */
@Mapper
public interface TicketStockMapper {

    /**
     * 批量插入库存，重复的跳过。
     *
     * <p>SQL 用 ON DUPLICATE KEY UPDATE id = id，
     * 靠唯一键挡掉重复，不会报错，也不会覆盖已有库存。
     */
    int batchInsertIgnore(@Param("list") List<TicketStock> list);

    /** 统计日期区间内库存总行数，生成前后相减可得到真实新增条数 */
    long countByDateRange(@Param("startDate") LocalDate startDate,
                          @Param("endDateExclusive") LocalDate endDateExclusive);

    /** 查某天某车次的全部库存（按段号、席别排序），带起止站名 */
    List<StockVO> findByTrainAndDate(@Param("trainId") Long trainId,
                                     @Param("travelDate") LocalDate travelDate);

    /** 按 id 查一条 */
    StockVO findById(@Param("id") Long id);

    /**
     * 手工调整剩余票数。
     * sold_count 同步改成 total_count - remaining，保证一致性。
     */
    int updateRemaining(@Param("id") Long id,
                        @Param("remaining") Integer remaining);
}
