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

    /** status 0 -> 1，同时记录 Redis 已经预扣成功。 */
    int markQueued(@Param("requestId") String requestId);

    /** 申请状态 1/2 -> 2；真正的重复消费保护由行锁和最终 status=3 完成。 */
    int markProcessing(@Param("requestId") String requestId);

    int markPublished(@Param("requestId") String requestId,
                      @Param("publishedAt") LocalDateTime publishedAt);

    int markSuccess(@Param("requestId") String requestId,
                    @Param("orderId") Long orderId,
                    @Param("orderNo") String orderNo);

    int markFailed(@Param("requestId") String requestId,
                   @Param("failReason") String failReason);

    /** 找出投递前进程崩溃、还没有成功发送到 RabbitMQ 的请求。 */
    List<OrderRequest> findUnpublishedQueued(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("limit") int limit);

    /** 找出预处理中或消费中卡死的请求，由恢复任务释放预扣并标记失败。 */
    List<OrderRequest> findStaleActive(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("limit") int limit);

    /**
     * 对账前检查当天车次是否仍有未完成的异步预扣。
     * status 0=已提交，1=已预扣待投递，2=消费中。
     */
    int countActiveReservations(
            @Param("travelDate") java.time.LocalDate travelDate,
            @Param("trainId") Long trainId);
}
