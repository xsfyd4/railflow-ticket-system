package com.ticket.module.ticket.impl;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.TicketService;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.mapper.TicketSearchMapper;
import com.ticket.module.ticket.vo.TicketSeatRow;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketServiceImpl implements TicketService {

    private final TicketSearchMapper mapper;

    public TicketServiceImpl(TicketSearchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<TicketVO> search(TicketSearchDTO q) {
        // 1. 兜底
        int page = (q.getPage() == null || q.getPage() < 1) ? 1 : q.getPage();
        int size = (q.getSize() == null || q.getSize() < 1) ? 10 : q.getSize();
        if (size > 50) {
            size = 50;
        }
        q.setPage(page);
        q.setSize(size);

        // 2. 先数车次
        long total = mapper.countTrains(q);
        if (total == 0) {
            return PageResult.of(0, page, size, List.of());
        }

        // 3. 取当页车次 id（分页在这一步，不会被席别放大）
        int offset = (page - 1) * size;
        List<Long> ids = mapper.findTrainIds(q, offset);
        if (ids.isEmpty()) {
            return PageResult.of(total, page, size, List.of());
        }

        // 4. 按 id 查席别余票（一行 = 一个车次 + 一个席别）
        List<TicketSeatRow> rows = mapper.findSeatRows(q, ids);

        // 5. 按车次合并席别
        Map<Long, TicketVO> byTrain = new HashMap<>();
        for (TicketSeatRow r : rows) {
            TicketVO vo = byTrain.computeIfAbsent(r.getTrainId(), id -> toVO(r));
            vo.getSeats().add(new TicketSeatVO(
                    r.getSeatType(), r.getRemainCount(), r.getPrice()));
        }

        // 6. 按 id 的原顺序输出（SQL 已经按发车时间排好）
        List<TicketVO> records = new ArrayList<>();
        for (Long id : ids) {
            TicketVO vo = byTrain.get(id);
            if (vo != null) {
                records.add(vo);
            }
        }
        return PageResult.of(total, page, size, records);
    }

    private TicketVO toVO(TicketSeatRow r) {
        TicketVO vo = new TicketVO();
        vo.setTrainId(r.getTrainId());
        vo.setTrainNo(r.getTrainNo());
        vo.setTrainType(r.getTrainType());
        vo.setFromStationName(r.getFromStationName());
        vo.setToStationName(r.getToStationName());
        vo.setDepartTime(r.getDepartTime());
        vo.setArriveTime(r.getArriveTime());
        vo.setDurationMinutes(r.getDurationMinutes());
        vo.setMileage(r.getMileage());
        return vo;
    }
}
