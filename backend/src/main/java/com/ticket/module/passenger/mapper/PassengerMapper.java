package com.ticket.module.passenger.mapper;

import com.ticket.module.passenger.entity.Passenger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** passenger 表的数据访问层。 */
@Mapper
public interface PassengerMapper {

    List<Passenger> findByUserId(@Param("userId") Long userId);

    Passenger findByIdAndUserId(@Param("id") Long id,
                                @Param("userId") Long userId);

    Passenger findByUserIdAndIdCardNo(@Param("userId") Long userId,
                                      @Param("idCardNo") String idCardNo);

    long countByUserId(@Param("userId") Long userId);

    int insert(Passenger passenger);

    int update(Passenger passenger);

    int deleteByIdAndUserId(@Param("id") Long id,
                            @Param("userId") Long userId);
}
