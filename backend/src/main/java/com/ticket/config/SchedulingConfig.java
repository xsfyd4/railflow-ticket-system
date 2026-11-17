package com.ticket.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 后台任务按业务域隔离，避免长时间维护任务阻塞订单恢复。
 */
@Configuration
public class SchedulingConfig {

    private static final Logger log =
            LoggerFactory.getLogger(SchedulingConfig.class);

    @Bean("orderTaskScheduler")
    public ThreadPoolTaskScheduler orderTaskScheduler() {
        return scheduler(
                2, "ticket-order-scheduler-", "订单调度任务执行失败");
    }

    @Bean("maintenanceTaskScheduler")
    public ThreadPoolTaskScheduler maintenanceTaskScheduler() {
        return scheduler(
                3, "ticket-maintenance-scheduler-", "维护任务执行失败");
    }

    private ThreadPoolTaskScheduler scheduler(
            int poolSize, String prefix, String errorMessage) {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setDaemon(true);
        /*
         * 定时任务可以随时从数据库游标、Redis 索引和订单状态重新恢复，
         * 因此关闭应用时不等待正在执行的调度任务。让 daemon 线程立即退出，
         * 避免测试 JVM 和容器优雅停机被后台任务额外拖住 30 秒。
         */
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setErrorHandler(error ->
                log.error(errorMessage, error));
        return scheduler;
    }
}
