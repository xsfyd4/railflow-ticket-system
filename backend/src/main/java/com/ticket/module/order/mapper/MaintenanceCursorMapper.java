package com.ticket.module.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 后台恢复任务的持久化游标。 */
@Mapper
public interface MaintenanceCursorMapper {

    Long findLastEventId(@Param("jobName") String jobName);

    Long findFullLastId(@Param("jobName") String jobName);

    int updateLastEventId(@Param("jobName") String jobName,
                          @Param("lastEventId") long lastEventId);

    int updateFullLastId(@Param("jobName") String jobName,
                         @Param("fullLastId") long fullLastId);
}
