package com.ticket.module.order.job;

import com.ticket.module.order.entity.OrderRequest;
import com.ticket.module.order.impl.AsyncOrderPublisher;
import com.ticket.module.order.impl.OrderRequestFailureService;
import com.ticket.module.order.mapper.OrderRequestMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步下单恢复任务，处理应用在“写数据库”和“发 MQ”之间崩溃的窗口。
 */
@Component
public class OrderRequestRecoveryJob {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.mq.OrderRequestRecoveryJob");

    private final OrderRequestMapper requestMapper;
    private final AsyncOrderPublisher publisher;
    private final OrderRequestFailureService failureService;
    private final int batchSize;
    private final long republishDelaySeconds;
    private final long staleMinutes;

    public OrderRequestRecoveryJob(
            OrderRequestMapper requestMapper,
            AsyncOrderPublisher publisher,
            OrderRequestFailureService failureService,
            @Value("${ticket.order.async.recovery-batch-size:100}")
            int batchSize,
            @Value("${ticket.order.async.republish-delay-seconds:30}")
            long republishDelaySeconds,
            @Value("${ticket.order.async.stale-minutes:10}")
            long staleMinutes) {
        this.requestMapper = requestMapper;
        this.publisher = publisher;
        this.failureService = failureService;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.republishDelaySeconds = Math.max(1, republishDelaySeconds);
        this.staleMinutes = Math.max(1, staleMinutes);
    }

    @Scheduled(
            fixedDelayString = "${ticket.order.async.recovery-scan-ms:30000}",
            initialDelayString = "${ticket.order.async.recovery-initial-delay-ms:30000}"
    )
    public void recover() {
        republishMissingMessages();
        failStaleRequests();
    }

    private void republishMissingMessages() {
        LocalDateTime before = LocalDateTime.now()
                .minusSeconds(republishDelaySeconds);
        List<OrderRequest> requests =
                requestMapper.findUnpublishedQueued(before, batchSize);
        for (OrderRequest request : requests) {
            try {
                publisher.publish(request.getRequestId());
                requestMapper.markPublished(
                        request.getRequestId(), LocalDateTime.now());
                log.warn("恢复任务补投异步下单消息，requestId={}",
                        request.getRequestId());
            } catch (Exception e) {
                // 保留 published_at=NULL，下一轮继续补投。
                log.error("恢复任务补投失败，requestId={}",
                        request.getRequestId(), e);
            }
        }
    }

    private void failStaleRequests() {
        LocalDateTime before = LocalDateTime.now().minusMinutes(staleMinutes);
        List<OrderRequest> requests =
                requestMapper.findStaleActive(before, batchSize);
        for (OrderRequest request : requests) {
            try {
                failureService.fail(request.getRequestId(),
                        "异步下单处理超时，请重新提交");
                log.warn("恢复任务终止卡死请求，requestId={}",
                        request.getRequestId());
            } catch (Exception e) {
                log.error("恢复任务终止请求失败，requestId={}",
                        request.getRequestId(), e);
            }
        }
    }
}
