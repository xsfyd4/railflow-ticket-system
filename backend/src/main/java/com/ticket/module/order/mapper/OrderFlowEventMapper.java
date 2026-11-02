package com.ticket.module.order.mapper;

import com.ticket.module.order.entity.OrderFlowEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** order_flow_event 状态流水。 */
@Mapper
public interface OrderFlowEventMapper {

    int append(@Param("requestId") String requestId,
               @Param("fromState") String fromState,
               @Param("toState") String toState,
               @Param("ownerToken") String ownerToken,
               @Param("detail") String detail);

    List<OrderFlowEvent> findDueAfter(
            @Param("eventId") long eventId,
            @Param("createdBefore") LocalDateTime createdBefore,
            @Param("limit") int limit);
}
