package com.ticket.module.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis UUID 幂等快状态集成测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RedisIdempotencyStoreIntegrationTest {

    private static final String DOMAIN = "ORDER";
    private static final String REQUEST_ID = "__redis_uuid_gate__";

    @Autowired
    private RedisIdempotencyStore store;

    @Autowired
    @Qualifier("writeRedisTemplate")
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.delete(store.key(DOMAIN, REQUEST_ID));
    }

    @Test
    void shouldAllowOnlyOneOwnerAndReplaySuccess() {
        RedisIdempotencyStore.AcquireResult first = store.acquire(
                DOMAIN, REQUEST_ID, 1L, "hash-a");
        assertEquals(RedisIdempotencyStore.Status.ACQUIRED,
                first.status());

        RedisIdempotencyStore.AcquireResult second = store.acquire(
                DOMAIN, REQUEST_ID, 1L, "hash-a");
        assertEquals(RedisIdempotencyStore.Status.PROCESSING,
                second.status());

        assertTrue(store.markSuccess(
                DOMAIN, REQUEST_ID, first.ownerToken(), "ORDER-1"));
        assertFalse(store.markFailed(
                DOMAIN, REQUEST_ID, second.ownerToken(), "old owner"));

        RedisIdempotencyStore.AcquireResult replay = store.acquire(
                DOMAIN, REQUEST_ID, 1L, "hash-a");
        assertEquals(RedisIdempotencyStore.Status.REPLAY,
                replay.status());
        assertEquals("ORDER-1", replay.state().resultNo());
        assertEquals("SUCCESS", replay.state().state());
    }

    @Test
    void shouldRejectDifferentUserOrRequestHash() {
        assertEquals(RedisIdempotencyStore.Status.ACQUIRED,
                store.acquire(DOMAIN, REQUEST_ID, 1L, "hash-a").status());

        assertEquals(RedisIdempotencyStore.Status.CONFLICT,
                store.acquire(DOMAIN, REQUEST_ID, 2L, "hash-a").status());
        assertEquals(RedisIdempotencyStore.Status.CONFLICT,
                store.acquire(DOMAIN, REQUEST_ID, 1L, "hash-b").status());
    }

    @Test
    void shouldAllowRetryAfterFailureAndReclaimStaleSuccessAfterDbMiss() {
        RedisIdempotencyStore.AcquireResult first = store.acquire(
                DOMAIN, REQUEST_ID, 1L, "hash-a");
        assertTrue(store.markFailed(
                DOMAIN, REQUEST_ID, first.ownerToken(), "temporary"));

        RedisIdempotencyStore.AcquireResult retry = store.acquire(
                DOMAIN, REQUEST_ID, 1L, "hash-a");
        assertEquals(RedisIdempotencyStore.Status.ACQUIRED,
                retry.status());
        assertTrue(store.markSuccess(
                DOMAIN, REQUEST_ID, retry.ownerToken(), "ORDER-2"));

        RedisIdempotencyStore.AcquireResult reclaimed =
                store.acquireAfterDatabaseMiss(
                        DOMAIN, REQUEST_ID, 1L, "hash-c");
        assertEquals(RedisIdempotencyStore.Status.ACQUIRED,
                reclaimed.status());
        assertNotNull(reclaimed.ownerToken());
    }

    @Test
    void shouldHaveExactlyOneOwnerUnderConcurrentAcquire() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(index -> pool.submit(() -> {
                        start.await();
                        return store.acquire(
                                DOMAIN, REQUEST_ID, 1L, "hash-a").status();
                    }))
                    .map(future -> (Future<RedisIdempotencyStore.Status>)
                            future)
                    .toList();
            start.countDown();

            long acquired = 0;
            long processing = 0;
            for (Future<RedisIdempotencyStore.Status> future : futures) {
                RedisIdempotencyStore.Status status =
                        future.get(10, TimeUnit.SECONDS);
                if (status == RedisIdempotencyStore.Status.ACQUIRED) {
                    acquired++;
                }
                if (status == RedisIdempotencyStore.Status.PROCESSING) {
                    processing++;
                }
            }
            assertEquals(1, acquired);
            assertEquals(5, processing);
        } finally {
            pool.shutdownNow();
        }
    }
}
