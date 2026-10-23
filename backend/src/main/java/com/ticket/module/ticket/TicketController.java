package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.common.result.R;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.vo.TicketVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * GET /api/user/tickets/search?fromStationId=1&toStationId=27&travelDate=2026-11-01
     */
    @GetMapping("/search")
    public R<PageResult<TicketVO>> search(@Valid TicketSearchDTO query) {
        return R.ok(ticketService.search(query));
    }
}
