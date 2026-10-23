package com.ticket.module.passenger;

import com.ticket.module.passenger.dto.PassengerSaveDTO;
import com.ticket.module.passenger.vo.PassengerVO;

import java.util.List;

/** 当前登录用户的常用乘车人。 */
public interface PassengerService {

    List<PassengerVO> list();

    Long create(PassengerSaveDTO dto);

    void update(Long id, PassengerSaveDTO dto);

    void delete(Long id);
}
