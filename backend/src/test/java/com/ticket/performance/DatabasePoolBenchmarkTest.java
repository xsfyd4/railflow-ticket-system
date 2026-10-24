package com.ticket.performance;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 手动执行的数据库访问层基准，默认不参与普通 mvn test。
 *
 * <pre>
 * mvn -Dbenchmark=true -Dtest=DatabasePoolBenchmarkTest test
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DatabasePoolBenchmarkTest {

    private static final int WORKERS =
            Integer.getInteger("benchmark.workers", 64);
    private static final int DURATION_SECONDS =
            Integer.getInteger("benchmark.duration", 4);
    private static final Date TRAVEL_DATE = Date.valueOf(LocalDate.of(2026, 10, 24));

    @Autowired
    private Environment environment;

    @Test
    @EnabledIfSystemProperty(named = "benchmark", matches = "true")
    void compareConnectionPools() throws Exception {
        BenchmarkResult pool10 = benchmark(10);
        BenchmarkResult pool20 = benchmark(20);

        System.out.printf(
                "POOL_BENCHMARK pool=10 qps=%.2f p50=%.2fms p95=%.2fms "
                        + "p99=%.2fms completed=%d errors=%d%n",
                pool10.qps(), pool10.p50Ms(), pool10.p95Ms(),
                pool10.p99Ms(), pool10.completed(), pool10.errors());
        System.out.printf(
                "POOL_BENCHMARK pool=20 qps=%.2f p50=%.2fms p95=%.2fms "
                        + "p99=%.2fms completed=%d errors=%d%n",
                pool20.qps(), pool20.p50Ms(), pool20.p95Ms(),
                pool20.p99Ms(), pool20.completed(), pool20.errors());

        assertEquals(0, pool10.errors());
        assertEquals(0, pool20.errors());
    }

    private BenchmarkResult benchmark(int poolSize) throws Exception {
        HikariDataSource dataSource = createPool(poolSize);
        try {
            for (int i = 0; i < 20; i++) {
                executeSearchQueries(dataSource);
            }

            ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
            CountDownLatch start = new CountDownLatch(1);
            AtomicLong completed = new AtomicLong();
            AtomicInteger errors = new AtomicInteger();
            List<Long> latencyNanos =
                    Collections.synchronizedList(new ArrayList<>());
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(DURATION_SECONDS);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    while (System.nanoTime() < deadline) {
                        long queryStarted = System.nanoTime();
                        try {
                            executeSearchQueries(dataSource);
                            latencyNanos.add(System.nanoTime() - queryStarted);
                            completed.incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            long benchmarkStarted = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            double elapsedSeconds =
                    (System.nanoTime() - benchmarkStarted) / 1_000_000_000.0;
            executor.shutdownNow();

            List<Long> sorted = new ArrayList<>(latencyNanos);
            Collections.sort(sorted);
            return new BenchmarkResult(
                    completed.get() / elapsedSeconds,
                    percentileMs(sorted, 0.50),
                    percentileMs(sorted, 0.95),
                    percentileMs(sorted, 0.99),
                    completed.get(),
                    errors.get());
        } finally {
            dataSource.close();
        }
    }

    private HikariDataSource createPool(int size) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("benchmark-pool-" + size);
        config.setJdbcUrl(required("spring.datasource.url"));
        config.setUsername(required("spring.datasource.username"));
        config.setPassword(required("spring.datasource.password"));
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    /**
     * 与 TicketServiceImpl 当前行为一致：三次 Mapper 查询分别借出和归还连接。
     */
    private void executeSearchQueries(HikariDataSource dataSource)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(DISTINCT t.id)
                     FROM train t
                     INNER JOIN train_stop stop_from
                             ON stop_from.train_id = t.id
                            AND stop_from.station_id = ?
                     INNER JOIN train_stop stop_to
                             ON stop_to.train_id = t.id
                            AND stop_to.station_id = ?
                     WHERE t.status = 1
                       AND stop_from.stop_order < stop_to.stop_order
                     """)) {
            statement.setLong(1, 1L);
            statement.setLong(2, 27L);
            statement.executeQuery().close();
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT t.id
                     FROM train t
                     INNER JOIN train_stop stop_from
                             ON stop_from.train_id = t.id
                            AND stop_from.station_id = ?
                     INNER JOIN train_stop stop_to
                             ON stop_to.train_id = t.id
                            AND stop_to.station_id = ?
                     WHERE t.status = 1
                       AND stop_from.stop_order < stop_to.stop_order
                     GROUP BY t.id
                     ORDER BY MIN(stop_from.departure_time), t.id
                     LIMIT 10
                     """)) {
            statement.setLong(1, 1L);
            statement.setLong(2, 27L);
            statement.executeQuery().close();
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT t.id, MIN(stock.remaining_count), SUM(price.price)
                     FROM train t
                     INNER JOIN train_stop stop_from
                             ON stop_from.train_id = t.id
                            AND stop_from.station_id = ?
                     INNER JOIN train_stop stop_to
                             ON stop_to.train_id = t.id
                            AND stop_to.station_id = ?
                     INNER JOIN train_segment seg
                             ON seg.train_id = t.id
                            AND seg.segment_index BETWEEN stop_from.stop_order
                                                      AND stop_to.stop_order - 1
                     INNER JOIN train_segment_price price
                             ON price.train_id = seg.train_id
                            AND price.segment_index = seg.segment_index
                            AND price.seat_type = ?
                     INNER JOIN ticket_stock stock
                             ON stock.train_id = seg.train_id
                            AND stock.segment_index = seg.segment_index
                            AND stock.seat_type = price.seat_type
                            AND stock.travel_date = ?
                     WHERE t.id IN (1, 2)
                     GROUP BY t.id, price.seat_type
                     """)) {
            statement.setLong(1, 1L);
            statement.setLong(2, 27L);
            statement.setInt(3, 3);
            statement.setDate(4, TRAVEL_DATE);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    resultSet.getLong(1);
                }
            }
        }
    }

    private double percentileMs(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = Math.min(sorted.size() - 1,
                (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(Math.max(index, 0)) / 1_000_000.0;
    }

    private String required(String key) {
        return environment.getRequiredProperty(key);
    }

    private record BenchmarkResult(double qps,
                                   double p50Ms,
                                   double p95Ms,
                                   double p99Ms,
                                   long completed,
                                   int errors) {
    }
}
