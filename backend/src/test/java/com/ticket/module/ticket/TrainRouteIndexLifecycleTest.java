package com.ticket.module.ticket;

import com.ticket.module.ticket.mapper.TrainRouteIndexMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainRouteIndexLifecycleTest {

    @Test
    void shouldUseRebuildingStateWhileNewIndexIsBuilt() throws Exception {
        TrainRouteIndexMapper mapper =
                mock(TrainRouteIndexMapper.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(mapper.findAllStopsForIndex()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        when(mapper.findAllPricesForIndex()).thenReturn(List.of());

        TrainRouteIndex index = new TrainRouteIndex(
                mapper, new SimpleMeterRegistry());
        try {
            index.requestRebuild("test");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(TrainRouteIndex.State.REBUILDING, index.state());

            release.countDown();
            awaitState(index, TrainRouteIndex.State.READY);
            assertTrue(index.isReady());
            assertEquals(1, index.version());
        } finally {
            release.countDown();
            index.shutdown();
        }
    }

    @Test
    void shouldFailClosedToUnavailableWhenBuildThrows() throws Exception {
        TrainRouteIndexMapper mapper =
                mock(TrainRouteIndexMapper.class);
        when(mapper.findAllStopsForIndex())
                .thenThrow(new IllegalStateException("db down"));
        TrainRouteIndex index = new TrainRouteIndex(
                mapper, new SimpleMeterRegistry());
        try {
            index.requestRebuild("test");
            awaitState(index, TrainRouteIndex.State.UNAVAILABLE);
            assertEquals(
                    TrainRouteIndex.State.UNAVAILABLE, index.state());
        } finally {
            index.shutdown();
        }
    }

    private void awaitState(TrainRouteIndex index,
                            TrainRouteIndex.State expected)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (index.state() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, index.state());
    }
}
