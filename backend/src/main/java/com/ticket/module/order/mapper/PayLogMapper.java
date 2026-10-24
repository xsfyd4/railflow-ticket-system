package com.ticket.module.order.mapper;

import com.ticket.module.order.entity.PayLog;
import com.ticket.module.order.vo.PayLogVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付流水数据访问接口。
 */
@Mapper
public interface PayLogMapper {

    /**
     * 插入一条“处理中”的支付流水。
     *
     * <p>{@code request_id} 唯一索引是支付幂等的第一道闸门：
     * 同一个幂等键并发插入时，只有一个请求能成功，其余请求会抛
     * {@link org.springframework.dao.DuplicateKeyException}，由上层转为“重放已有结果”。
     */
    int insert(PayLog payLog);

    /** 按幂等键查询支付流水。 */
    PayLog findByRequestId(@Param("requestId") String requestId);

    /** 查询某订单唯一的一条成功流水。 */
    PayLog findSuccessByOrderId(@Param("orderId") Long orderId);

    /** 查询某订单的全部支付尝试，按时间倒序。 */
    List<PayLogVO> findByOrderNoAndUser(@Param("orderNo") String orderNo,
                                        @Param("userId") Long userId);

    /** 只有“处理中”的流水才能被置为成功，防止重复回调改坏已关闭的记录。 */
    int markSuccess(@Param("id") Long id,
                    @Param("externalTxnId") String externalTxnId,
                    @Param("paidAt") LocalDateTime paidAt);

    /** 把处理中的流水置为失败（2）或已关闭（3）。 */
    int finishWithoutPayment(@Param("id") Long id,
                             @Param("status") int status,
                             @Param("reason") String reason);
}
