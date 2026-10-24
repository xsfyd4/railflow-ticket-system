package com.ticket.performance;

import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.mapper.TicketSearchMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 交替对比三次 Mapper 查询在“无事务”和“只读事务”下的数据库访问性能。
 *
 * <pre>
 * mvn -Dbenchmark=true -Dtest=ReadTransactionBenchmarkTest test
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReadTransactionBenchmarkTest {

    private static final int WORKERS =
            Integer.getInteger("benchmark.workers", 64);
    private static final int DURATION_SECONDS =
            Integer.getInteger("benchmark.duration", 2);
    private static final int ROUNDS =
            Integer.getInteger("benchmark.rounds", 3);
    private static final int WARMUP_SECONDS =
            Integer.getInteger("benchmark.warmup", 1);

    @Autowired
    private TicketSearchMapper mapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @EnabledIfSystemProperty(named = "benchmark", matches = "true")
    void compareReadTransaction() throws Exception {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        List<BenchmarkResult> noTransactionResults = new ArrayList<>();
        List<BenchmarkResult> transactionResults = new ArrayList<>();

        for (int round = 1; round <= ROUNDS; round++) {
            if (round % 2 == 1) {
                noTransactionResults.add(run("no-tx", this::queryWithoutTransaction));
                transactionResults.add(run("read-only-tx",
                        () -> readOnly.executeWithoutResult(
                                status -> queryMappers())));
            } else {
                transactionResults.add(run("read-only-tx",
                        () -> readOnly.executeWithoutResult(
                                status -> queryMappers())));
                noTransactionResults.add(run("no-tx", this::queryWithoutTransaction));
            }
        }

        print("no-tx", noTransactionResults);
        print("read-only-tx", transactionResults);
        assertEquals(0, noTransactionResults.stream()
                .mapToInt(BenchmarkResult::errors).sum());
        assertEquals(0, transactionResults.stream()
                .mapToInt(BenchmarkResult::errors).sum());
    }

    private BenchmarkResult run(String label, Runnable query) throws Exception {
        for (int i = 0; i < 20; i++) {
            query.run();
        }
        warmUpConcurrently(query);

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
                    long started = System.nanoTime();
                    try {
                        query.run();
                        latencyNanos.add(System.nanoTime() - started);
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
                label,
                completed.get() / elapsedSeconds,
                percentileMs(sorted, 0.50),
                percentileMs(sorted, 0.95),
                completed.get(),
                errors.get());
    }

    private void warmUpConcurrently(Runnable query) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                while (System.nanoTime() < deadline) {
                    query.run();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
    }

    private void queryWithoutTransaction() {
        queryMappers();
    }

    private void queryMappers() {
        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(LocalDate.of(2026, 10, 24));
        query.setPage(1);
        query.setSize(10);
        query.setSeatType(3);

        mapper.countTrains(query);
        List<Long> ids = mapper.findTrainIds(query, 0);
        mapper.findSeatRows(query, ids);
    }

    private void print(String name, List<BenchmarkResult> results) {
        double averageQps = results.stream()
                .mapToDouble(BenchmarkResult::qps).average().orElse(0);
        double averageP95 = results.stream()
                .mapToDouble(BenchmarkResult::p95Ms).average().orElse(0);
        System.out.printf(
                "READ_TX_BENCHMARK scenario=%s rounds=%d avgQps=%.2f "
                        + "avgP95=%.2fms%n",
                name, results.size(), averageQps, averageP95);
        for (BenchmarkResult result : results) {
            System.out.printf(
                    "READ_TX_ROUND scenario=%s qps=%.2f p50=%.2fms "
                            + "p95=%.2fms completed=%d errors=%d%n",
                    name, result.qps(), result.p50Ms(), result.p95Ms(),
                    result.completed(), result.errors());
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

    private record BenchmarkResult(String scenario,
                                   double qps,
                                   double p50Ms,
                                   double p95Ms,
                                   long completed,
                                   int errors) {
    }
}
