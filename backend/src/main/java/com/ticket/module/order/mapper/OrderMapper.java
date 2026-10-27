package com.ticket.module.order.mapper;

import com.ticket.module.order.entity.TicketOrder;
import com.ticket.module.order.entity.TicketOrderItem;
import com.ticket.module.order.vo.OrderDetailVO;
import com.ticket.module.order.vo.OrderItemVO;
import com.ticket.module.order.vo.OrderRouteVO;
import com.ticket.module.order.vo.OrderVO;
import com.ticket.module.train.vo.TrainPriceVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单主表和明细表的数据访问接口。
 */
@Mapper
public interface OrderMapper {

    /** 根据车次和两个车站确认运行方向，返回站序；查不到返回 null。 */
    OrderRouteVO findRoute(@Param("trainId") Long trainId,
                           @Param("fromStationId") Long fromStationId,
                           @Param("toStationId") Long toStationId);

    /** 查询区间内每一段的指定席别价格。 */
    List<TrainPriceVO> findPrices(@Param("trainId") Long trainId,
                                  @Param("seatType") Integer seatType,
                                  @Param("fromSegmentIndex") Integer fromSegmentIndex,
                                  @Param("toSegmentIndex") Integer toSegmentIndex);

    /**
     * 条件扣减库存。
     *
     * <p>任何一段余票不足时，该段不会更新，返回行数会小于覆盖段数，
     * Service 据此抛异常并让整个事务回滚。
     */
    int deductStock(@Param("travelDate") LocalDate travelDate,
                    @Param("trainId") Long trainId,
                    @Param("seatType") Integer seatType,
                    @Param("fromSegmentIndex") Integer fromSegmentIndex,
                    @Param("toSegmentIndex") Integer toSegmentIndex,
                    @Param("count") Integer count);

    int insertOrder(TicketOrder order);

    /** 一次 SQL 插入某订单的全部乘车人明细。 */
    int batchInsertItems(@Param("items") List<TicketOrderItem> items);

    long countByUser(@Param("userId") Long userId,
                     @Param("status") Integer status);

    List<OrderVO> findPageByUser(@Param("userId") Long userId,
                                 @Param("status") Integer status,
                                 @Param("offset") int offset,
                                 @Param("size") int size);

    OrderDetailVO findDetailByUserAndNo(@Param("userId") Long userId,
                                        @Param("orderNo") String orderNo);

    List<OrderItemVO> findItemsByOrderId(@Param("orderId") Long orderId);

    /** 锁定一行订单，防止支付和取消并发执行。 */
    TicketOrder findByOrderNoAndUserForUpdate(
            @Param("orderNo") String orderNo,
            @Param("userId") Long userId);

    /** 普通读取订单（不加锁），用于支付流水建账前的归属校验。 */
    TicketOrder findByOrderNoAndUser(
            @Param("orderNo") String orderNo,
            @Param("userId") Long userId);

    /** 按同步下单幂等键查订单（不限制用户，便于识别幂等键被别的账号占用）。 */
    TicketOrder findByRequestId(@Param("requestId") String requestId);

    /** 只有待支付订单才能改成已支付。 */
    int markPaid(@Param("id") Long id,
                 @Param("paidAt") LocalDateTime paidAt);

    /** 只有待支付订单才能改成已取消。 */
    int markCancelled(@Param("id") Long id,
                      @Param("cancelledAt") LocalDateTime cancelledAt);

    /** 按订单保存的起止段区间回补库存。 */
    int restoreStock(@Param("travelDate") LocalDate travelDate,
                     @Param("trainId") Long trainId,
                     @Param("seatType") Integer seatType,
                     @Param("fromSegmentIndex") Integer fromSegmentIndex,
                     @Param("toSegmentIndex") Integer toSegmentIndex,
                     @Param("count") Integer count);

    /** 查询已经到期、仍处于待支付状态的订单 id。 */
    List<Long> findExpiredPendingOrderIds(@Param("limit") int limit);

    /** 定时任务按主键锁定订单，不依赖登录用户。 */
    TicketOrder findByIdForUpdate(@Param("id") Long id);

    /** 锁定本次退票选中的订单明细。 */
    List<TicketOrderItem> findItemsByIdsForUpdate(
            @Param("orderId") Long orderId,
            @Param("itemIds") List<Long> itemIds);

    int markItemsRefunded(@Param("orderId") Long orderId,
                          @Param("itemIds") List<Long> itemIds,
                          @Param("refundedAt") LocalDateTime refundedAt);

    long countUnrefundedItems(@Param("orderId") Long orderId);

    long countRefundedItems(@Param("orderId") Long orderId);

    int updateRefundSummary(@Param("orderId") Long orderId,
                            @Param("refundedCount") int refundedCount,
                            @Param("refundAmount") java.math.BigDecimal refundAmount,
                            @Param("refundedAt") LocalDateTime refundedAt,
                            @Param("status") int status);
}
