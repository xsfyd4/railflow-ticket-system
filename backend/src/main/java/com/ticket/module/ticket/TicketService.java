package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketVO;

/** 用户端查票业务。 */
public interface TicketService {

    /** 查票：返回当页车次，每条带所有席别的余票和票价 */
    PageResult<TicketVO> search(TicketSearchDTO query);
}
