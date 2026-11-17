package com.ticket.module.order.mapper;

import com.ticket.module.order.entity.OrderRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** order_request 异步下单状态表的数据访问层。 */
@Mapper
public interface OrderRequestMapper {

    int insert(OrderRequest request);

    OrderRequest findByRequestId(@Param("requestId") String requestId);

    OrderRequest findByRequestIdAndUserId(
            @Param("requestId") String requestId,
            @Param("userId") Long userId);

    OrderRequest findByRequestIdForUpdate(
            @Param("requestId") String requestId);

    int markReserved(@Param("requestId") String requestId,
                     @Param("ownerToken") String ownerToken,
                     @Param("nextRetryAt") LocalDateTime nextRetryAt);

    int markPublished(@Param("requestId") String requestId,
                      @Param("publishedAt") LocalDateTime publishedAt);

    int markSuccess(@Param("requestId") String requestId,
                    @Param("fromState") String fromState,
                    @Param("orderId") Long orderId,
                    @Param("orderNo") String orderNo);

    int deferPublishing(@Param("requestId") String requestId,
                        @Param("lastError") String lastError,
                        @Param("nextRetryAt") LocalDateTime nextRetryAt);

    int markReleasing(@Param("requestId") String requestId,
                      @Param("failReason") String failReason,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt);

    int markStockReleased(@Param("requestId") String requestId);

    int markManualReview(@Param("requestId") String requestId,
                         @Param("failReason") String failReason);

    List<OrderRequest> findRecoverablePage(
            @Param("lastId") long lastId,
            @Param("limit") int limit);

    /**
     * 对账前检查当天车次是否仍有未完成的异步预扣。
     * status 0=已提交，1=已预扣待投递，2=消费中，5=等待释放库存；
     * 兼容旧数据中 status=4 但仍处于补偿中的请求。
     */
    int countActiveReservations(
            @Param("travelDate") java.time.LocalDate travelDate,
            @Param("trainId") Long trainId);
}
