package com.ticket.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 手动执行的 Spring MVC 进程内基准，不经过真实 Tomcat TCP。
 *
 * <pre>
 * mvn -Dbenchmark=true -Dtest=HttpPipelineBenchmarkTest test
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpPipelineBenchmarkTest {

    private static final int WORKERS =
            Integer.getInteger("benchmark.workers", 64);
    private static final int DURATION_SECONDS =
            Integer.getInteger("benchmark.duration", 4);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @EnabledIfSystemProperty(named = "benchmark", matches = "true")
    void benchmarkSpringPipeline() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"zhangsan","password":"123456"}
                                """))
                .andReturn();
        JsonNode loginBody = objectMapper.readTree(
                login.getResponse().getContentAsByteArray());
        assertTrue(loginBody.path("code").asInt() == 200, "测试用户登录失败");
        String token = loginBody.path("data").path("token").asString();
        String path = "/api/user/tickets/search"
                + "?fromStationId=1&toStationId=27"
                + "&travelDate=2026-10-24&page=1&size=10";

        for (int i = 0; i < 20; i++) {
            performSearch(token, path);
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
                    long requestStarted = System.nanoTime();
                    try {
                        performSearch(token, path);
                        latencyNanos.add(System.nanoTime() - requestStarted);
                        completed.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        long stableCheckStart = System.nanoTime();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        double elapsedSeconds =
                (System.nanoTime() - stableCheckStart) / 1_000_000_000.0;
        executor.shutdownNow();

        List<Long> sorted = new ArrayList<>(latencyNanos);
        Collections.sort(sorted);
        System.out.printf(
                "HTTP_PIPELINE_BENCHMARK qps=%.2f p50=%.2fms p95=%.2fms "
                        + "p99=%.2fms completed=%d errors=%d%n",
                completed.get() / elapsedSeconds,
                percentileMs(sorted, 0.50),
                percentileMs(sorted, 0.95),
                percentileMs(sorted, 0.99),
                completed.get(),
                errors.get());
        assertEquals(0, errors.get());
    }

    private void performSearch(String token, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException(
                    "HTTP status=" + result.getResponse().getStatus());
        }
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray());
        if (body.path("code").asInt() != 200) {
            throw new IllegalStateException("business code=" + body.path("code"));
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
}
