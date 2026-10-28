package com.ticket.module.ticket.impl;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.TicketSearchCache;
import com.ticket.module.ticket.TrainRouteIndex;
import com.ticket.module.ticket.dto.TicketSearchDTO;
import com.ticket.module.ticket.mapper.TicketSearchMapper;
import com.ticket.module.ticket.vo.TicketVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketServiceRoutingTest {

    @Test
    void shouldUseMysqlSlowQueryWhenIndexIsNotReady() {
        TicketSearchMapper mapper = mock(TicketSearchMapper.class);
        TicketSearchCache cache = mock(TicketSearchCache.class);
        TrainRouteIndex index = mock(TrainRouteIndex.class);
        IndexedTicketSearchService indexed =
                mock(IndexedTicketSearchService.class);
        when(index.isReady()).thenReturn(false);
        when(cache.currentToken(any()))
                .thenReturn(new TicketSearchCache.CacheToken(
                        false, 0, 0, 0));
        when(mapper.countTrains(any())).thenReturn(0L);

        TicketServiceImpl service = service(
                mapper, cache, index, indexed);
        service.search(query());

        verify(mapper).countTrains(any());
        verify(indexed, never()).search(any());
    }

    @Test
    void shouldUseIndexWhenReadyAndRedisMisses() {
        TicketSearchMapper mapper = mock(TicketSearchMapper.class);
        TicketSearchCache cache = mock(TicketSearchCache.class);
        TrainRouteIndex index = mock(TrainRouteIndex.class);
        IndexedTicketSearchService indexed =
                mock(IndexedTicketSearchService.class);
        when(cache.isAvailable()).thenReturn(true);
        when(index.isReady()).thenReturn(true);
        when(index.hasRoute(1L, 27L)).thenReturn(true);
        when(cache.currentToken(any()))
                .thenReturn(new TicketSearchCache.CacheToken(
                        true, 1, 1, 1));
        when(cache.get(any(), any())).thenReturn(null);
        when(cache.tryAcquireRebuildLock(any())).thenReturn(
                TicketSearchCache.LockToken.acquired(
                        "cache-key", "owner"));
        PageResult<TicketVO> expected =
                PageResult.of(0, 1, 10, List.of());
        when(indexed.search(any())).thenReturn(expected);

        TicketServiceImpl service = service(
                mapper, cache, index, indexed);
        PageResult<TicketVO> actual = service.search(query());

        assertSame(expected, actual);
        verify(indexed).search(any());
        verify(mapper, never()).countTrains(any());
    }

    @Test
    void shouldUseIndexWhenRedisIsUnavailableButIndexIsReady() {
        TicketSearchMapper mapper = mock(TicketSearchMapper.class);
        TicketSearchCache cache = mock(TicketSearchCache.class);
        TrainRouteIndex index = mock(TrainRouteIndex.class);
        IndexedTicketSearchService indexed =
                mock(IndexedTicketSearchService.class);
        when(index.isReady()).thenReturn(true);
        when(index.hasRoute(1L, 27L)).thenReturn(true);
        when(cache.currentToken(any())).thenReturn(
                TicketSearchCache.CacheToken.disabled());
        PageResult<TicketVO> expected =
                PageResult.of(0, 1, 10, List.of());
        when(indexed.search(any())).thenReturn(expected);

        TicketServiceImpl service = service(
                mapper, cache, index, indexed);
        PageResult<TicketVO> actual = service.search(query());

        assertSame(expected, actual);
        verify(indexed).search(any());
        verify(mapper, never()).countTrains(any());
    }

    @Test
    void shouldReturnEmptyWithoutCacheOrDatabaseForUnknownRoute() {
        TicketSearchMapper mapper = mock(TicketSearchMapper.class);
        TicketSearchCache cache = mock(TicketSearchCache.class);
        TrainRouteIndex index = mock(TrainRouteIndex.class);
        IndexedTicketSearchService indexed =
                mock(IndexedTicketSearchService.class);
        when(index.isReady()).thenReturn(true);
        when(index.hasRoute(1L, 27L)).thenReturn(false);

        TicketServiceImpl service = service(
                mapper, cache, index, indexed);
        PageResult<TicketVO> result = service.search(query());

        assertEquals(0, result.getTotal());
        verify(cache, never()).currentToken(any());
        verify(cache, never()).get(any(), any());
        verify(indexed, never()).search(any());
        verify(mapper, never()).countTrains(any());
    }

    @Test
    void shouldUseCacheFilledByLockWinnerWithoutDuplicateLoad() {
        TicketSearchMapper mapper = mock(TicketSearchMapper.class);
        TicketSearchCache cache = mock(TicketSearchCache.class);
        TrainRouteIndex index = mock(TrainRouteIndex.class);
        IndexedTicketSearchService indexed =
                mock(IndexedTicketSearchService.class);
        PageResult<TicketVO> expected =
                PageResult.of(0, 1, 10, List.of());
        when(index.isReady()).thenReturn(true);
        when(index.hasRoute(1L, 27L)).thenReturn(true);
        when(cache.currentToken(any())).thenReturn(
                new TicketSearchCache.CacheToken(true, 1, 1, 1));
        when(cache.get(any(), any())).thenReturn(null, expected);
        when(cache.tryAcquireRebuildLock(any())).thenReturn(
                TicketSearchCache.LockToken.contended("cache-key"));

        TicketServiceImpl service = service(
                mapper, cache, index, indexed);
        PageResult<TicketVO> actual = service.search(query());

        assertSame(expected, actual);
        verify(indexed, never()).search(any());
        verify(mapper, never()).countTrains(any());
    }

    @Test
    void shouldRetryLockAfterContentionBeforeFallingBack() {
        TicketSearchMapper mapper = mock(TicketSearchMapper.class);
        TicketSearchCache cache = mock(TicketSearchCache.class);
        TrainRouteIndex index = mock(TrainRouteIndex.class);
        IndexedTicketSearchService indexed =
                mock(IndexedTicketSearchService.class);
        PageResult<TicketVO> expected =
                PageResult.of(0, 1, 10, List.of());
        when(index.isReady()).thenReturn(true);
        when(index.hasRoute(1L, 27L)).thenReturn(true);
        when(cache.currentToken(any())).thenReturn(
                new TicketSearchCache.CacheToken(true, 1, 1, 1));
        when(cache.get(any(), any())).thenReturn(null);
        when(cache.tryAcquireRebuildLock(any())).thenReturn(
                TicketSearchCache.LockToken.contended("cache-key"),
                TicketSearchCache.LockToken.acquired(
                        "cache-key", "owner"));
        when(indexed.search(any())).thenReturn(expected);

        TicketServiceImpl service = service(
                mapper, cache, index, indexed);
        PageResult<TicketVO> actual = service.search(query());

        assertSame(expected, actual);
        verify(indexed).search(any());
        verify(mapper, never()).countTrains(any());
    }

    private TicketServiceImpl service(
            TicketSearchMapper mapper,
            TicketSearchCache cache,
            TrainRouteIndex index,
            IndexedTicketSearchService indexed) {
        return new TicketServiceImpl(
                mapper, cache, index, indexed,
                new SimpleMeterRegistry(), 3, 1);
    }

    private TicketSearchDTO query() {
        TicketSearchDTO query = new TicketSearchDTO();
        query.setFromStationId(1L);
        query.setToStationId(27L);
        query.setTravelDate(LocalDate.of(2026, 10, 30));
        query.setSeatType(3);
        query.setPage(1);
        query.setSize(10);
        return query;
    }
}
