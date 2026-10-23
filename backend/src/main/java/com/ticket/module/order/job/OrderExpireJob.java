package com.ticket.module.order.job;

import com.ticket.module.order.impl.OrderExpireTxService;
import com.ticket.module.order.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 定时取消超过支付截止时间、但仍处于待支付状态的订单。 */
@Component
public class OrderExpireJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpireJob.class);

    private final OrderMapper orderMapper;
    private final OrderExpireTxService expireTxService;
    private final int batchSize;

    public OrderExpireJob(OrderMapper orderMapper,
                          OrderExpireTxService expireTxService,
                          @Value("${ticket.order.expire-batch-size:100}") int batchSize) {
        this.orderMapper = orderMapper;
        this.expireTxService = expireTxService;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(
            fixedDelayString = "${ticket.order.expire-scan-ms:60000}",
            initialDelayString = "${ticket.order.expire-initial-delay-ms:30000}"
    )
    public void expireOrders() {
        List<Long> ids = orderMapper.findExpiredPendingOrderIds(batchSize);
        int expiredCount = 0;
        int failedCount = 0;

        for (Long orderId : ids) {
            try {
                /*
                 * 每个订单单独调用一次事务服务。
                 * 这样第 2 个订单出错时，不会把第 1 个已经取消成功的订单一起回滚。
                 */
                if (expireTxService.expireOne(orderId)) {
                    expiredCount++;
                }
            } catch (Exception e) {
                failedCount++;
                // 必须记录订单 id，否则生产环境很难知道哪一单库存异常。
                log.error("自动取消过期订单失败，orderId={}", orderId, e);
            }
        }

        if (expiredCount > 0 || failedCount > 0) {
            log.info("过期订单扫描完成：扫描={}，取消={}，失败={}",
                    ids.size(), expiredCount, failedCount);
        }
    }
}
