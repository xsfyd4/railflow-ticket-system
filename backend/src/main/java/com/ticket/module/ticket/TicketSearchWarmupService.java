package com.ticket.module.ticket;

import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.mapper.TicketSearchMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 热门站对查票对象预热。
 *
 * <p>不是预计算全国所有组合，而是按配置限制线路数、未来天数和席别，
 * 复用正式查票链路生成同样的单对象数组快照。这样可以控制启动成本，
 * 又能在高峰开始前把最常见的第一页结果放到 Redis。
 */
@Service
public class TicketSearchWarmupService {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.SearchWarmup");

    private final TicketSearchMapper mapper;
    private final TicketService ticketService;
    private final Counter snapshots;

    public TicketSearchWarmupService(
            TicketSearchMapper mapper,
            TicketService ticketService,
            MeterRegistry meterRegistry,
            @Value("${ticket.redis.warmup.max-routes:40}")
            int maxRoutes,
            @Value("${ticket.redis.warmup.days:2}")
            int days,
            @Value("${ticket.redis.warmup.page-size:10}")
            int pageSize) {
        this.mapper = mapper;
        this.ticketService = ticketService;
        this.maxRoutes = Math.max(1, maxRoutes);
        this.days = Math.max(1, days);
        this.pageSize = Math.max(1, Math.min(50, pageSize));
        this.snapshots = meterRegistry.counter(
                "ticket.cache.search.warmed");
    }

    private final int maxRoutes;
    private final int days;
    private final int pageSize;

    public int warmup() {
        List<TicketSearchDTO> pairs =
                mapper.findWarmupStationPairs(maxRoutes);
        int count = 0;
        LocalDate today = LocalDate.now();
        for (TicketSearchDTO pair : pairs) {
            for (int day = 0; day < days; day++) {
                for (int seatType = 1; seatType <= 3; seatType++) {
                    TicketSearchDTO query = new TicketSearchDTO();
                    query.setFromStationId(pair.getFromStationId());
                    query.setToStationId(pair.getToStationId());
                    query.setTravelDate(today.plusDays(day));
                    query.setSeatType(seatType);
                    query.setPage(1);
                    query.setSize(pageSize);
                    try {
                        ticketService.search(query);
                        snapshots.increment();
                        count++;
                    } catch (Exception e) {
                        log.warn("查票对象预热失败，from={}，to={}，date={}，seat={}：{}",
                                query.getFromStationId(),
                                query.getToStationId(),
                                query.getTravelDate(),
                                seatType,
                                e.getMessage());
                    }
                }
            }
        }
        log.info("查票对象预热完成，线路={}，席别查询={}",
                pairs.size(), count);
        return count;
    }
}
