package com.ticket.module.ticket;

import com.ticket.module.ticket.dto.TrainRouteStopRow;
import com.ticket.module.ticket.mapper.TrainRouteIndexMapper;
import com.ticket.module.train.entity.TrainSegmentPrice;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 静态车段车次索引。
 *
 * <p>重建期间状态立即变成 REBUILDING，查询线程不会等待，而是走 MySQL
 * 三阶段慢查询。新索引在后台完整构建后通过 AtomicReference 原子替换。
 */
@Component
public class TrainRouteIndex {

    private static final Logger log =
            LoggerFactory.getLogger("com.ticket.redis.RouteIndex");
    private static final LocalDate DURATION_BASE = LocalDate.of(2000, 1, 1);

    private final TrainRouteIndexMapper mapper;
    private final ExecutorService executor;
    private final AtomicReference<State> state =
            new AtomicReference<>(State.NEW);
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>();
    private final AtomicBoolean rebuildRequested = new AtomicBoolean();
    private final AtomicBoolean workerRunning = new AtomicBoolean();
    private final Counter buildSuccess;
    private final Counter buildFailure;

    public TrainRouteIndex(TrainRouteIndexMapper mapper,
                           MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "route-index-builder");
            thread.setDaemon(true);
            return thread;
        });
        this.buildSuccess = meterRegistry.counter(
                "ticket.route.index.build.success");
        this.buildFailure = meterRegistry.counter(
                "ticket.route.index.build.failure");
        Gauge.builder("ticket.route.index.ready", this,
                        index -> index.isReady() ? 1 : 0)
                .register(meterRegistry);
        Gauge.builder("ticket.route.index.routes", this,
                        index -> index.routeCount())
                .register(meterRegistry);
        Gauge.builder("ticket.route.index.train.relations", this,
                        index -> index.trainRelationCount())
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        requestRebuild("application-startup");
    }

    public boolean isReady() {
        return state.get() == State.READY && current.get() != null;
    }

    public RouteBucket lookup(Long fromStationId, Long toStationId) {
        Snapshot snapshot = current.get();
        if (snapshot == null || state.get() != State.READY) {
            return null;
        }
        return snapshot.routes().get(
                new RouteKey(fromStationId, toStationId));
    }

    /**
     * 索引已就绪时判断站对是否存在可达车次。
     *
     * <p>调用方必须先确认 {@link #isReady()}，否则 false 也可能表示
     * 索引正在构建，而不是该站对真的不可达。
     */
    public boolean hasRoute(Long fromStationId, Long toStationId) {
        return lookup(fromStationId, toStationId) != null;
    }

    public State state() {
        return state.get();
    }

    public long version() {
        Snapshot snapshot = current.get();
        return snapshot == null ? 0L : snapshot.version();
    }

    public LocalDateTime builtAt() {
        Snapshot snapshot = current.get();
        return snapshot == null ? null : snapshot.builtAt();
    }

    public int routeCount() {
        Snapshot snapshot = current.get();
        return snapshot == null ? 0 : snapshot.routes().size();
    }

    public int trainRelationCount() {
        Snapshot snapshot = current.get();
        return snapshot == null ? 0 : snapshot.relationCount();
    }

    /**
     * 标记索引失效并异步重建。状态切换在调用线程立即完成，
     * 之后的查票请求会立刻进入慢查询。
     */
    public void requestRebuild(String reason) {
        beginRebuild(reason);
        startRebuild();
    }

    /**
     * 只标记失效，不启动后台任务。调用方可先完成缓存版本失效，
     * 再调用 {@link #startRebuild()}。
     */
    public void beginRebuild(String reason) {
        state.set(State.REBUILDING);
        rebuildRequested.set(true);
        log.info("车段车次索引进入重建状态，reason={}", reason);
    }

    public void startRebuild() {
        submitWorkerIfNeeded();
    }

    @Scheduled(
            initialDelayString = "${ticket.route-index.retry-delay-ms:30000}",
            fixedDelayString = "${ticket.route-index.retry-delay-ms:30000}")
    public void retryIfUnavailable() {
        if (state.get() == State.UNAVAILABLE) {
            requestRebuild("scheduled-retry");
        }
    }

    private void submitWorkerIfNeeded() {
        if (!workerRunning.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::drainRebuildRequests);
    }

    private void drainRebuildRequests() {
        try {
            do {
                rebuildRequested.set(false);
                buildAndSwap();
            } while (rebuildRequested.get());
        } finally {
            workerRunning.set(false);
            if (rebuildRequested.get()) {
                submitWorkerIfNeeded();
            }
        }
    }

    private void buildAndSwap() {
        long startedNanos = System.nanoTime();
        try {
            Snapshot snapshot = buildSnapshot();
            current.set(snapshot);
            state.set(State.READY);
            buildSuccess.increment();
            long elapsedMs = Duration.ofNanos(
                    System.nanoTime() - startedNanos).toMillis();
            log.info("车段车次索引构建成功，version={}，routes={}，relations={}，耗时={}ms",
                    snapshot.version(), snapshot.routes().size(),
                    snapshot.relationCount(), elapsedMs);
        } catch (Exception e) {
            state.set(State.UNAVAILABLE);
            buildFailure.increment();
            log.error("车段车次索引构建失败，查询继续使用 MySQL 慢查询", e);
        }
    }

    private Snapshot buildSnapshot() {
        List<TrainRouteStopRow> stops = mapper.findAllStopsForIndex();
        List<TrainSegmentPrice> prices = mapper.findAllPricesForIndex();
        Map<Long, List<TrainRouteStopRow>> stopsByTrain =
                new LinkedHashMap<>();
        for (TrainRouteStopRow stop : stops) {
            stopsByTrain.computeIfAbsent(
                    stop.getTrainId(), ignored -> new ArrayList<>())
                    .add(stop);
        }

        Map<Long, Map<Integer, Map<Integer, BigDecimal>>> priceByTrainSeat =
                new HashMap<>();
        for (TrainSegmentPrice price : prices) {
            priceByTrainSeat
                    .computeIfAbsent(price.getTrainId(),
                            ignored -> new HashMap<>())
                    .computeIfAbsent(price.getSeatType(),
                            ignored -> new HashMap<>())
                    .put(price.getSegmentIndex(), price.getPrice());
        }

        Map<RouteKey, List<MutableEntry>> mutable =
                new HashMap<>();
        for (List<TrainRouteStopRow> trainStops
                : stopsByTrain.values()) {
            trainStops.sort(Comparator.comparing(
                    TrainRouteStopRow::getStopOrder));
            for (int from = 0; from < trainStops.size() - 1; from++) {
                for (int to = from + 1; to < trainStops.size(); to++) {
                    MutableEntry entry = buildEntry(
                            trainStops, from, to, priceByTrainSeat);
                    if (entry == null) {
                        continue;
                    }
                    RouteKey key = new RouteKey(
                            entry.fromStationId, entry.toStationId);
                    mutable.computeIfAbsent(key,
                            ignored -> new ArrayList<>()).add(entry);
                }
            }
        }

        Map<RouteKey, RouteBucket> routes = new HashMap<>();
        int relations = 0;
        for (Map.Entry<RouteKey, List<MutableEntry>> item
                : mutable.entrySet()) {
            List<MutableEntry> entries = item.getValue();
            entries.sort(Comparator
                    .comparingInt((MutableEntry entry)
                            -> entry.departMinuteOfWeek)
                    .thenComparingLong(entry -> entry.trainId));
            RouteBucket bucket = toBucket(
                    item.getKey(), entries);
            routes.put(item.getKey(), bucket);
            relations += entries.size();
        }
        routes = Map.copyOf(routes);
        long version = current.get() == null
                ? 1 : current.get().version() + 1;
        return new Snapshot(version, LocalDateTime.now(),
                routes, relations);
    }

    private MutableEntry buildEntry(
            List<TrainRouteStopRow> stops,
            int fromIndex,
            int toIndex,
            Map<Long, Map<Integer, Map<Integer, BigDecimal>>>
                    priceByTrainSeat) {
        TrainRouteStopRow from = stops.get(fromIndex);
        TrainRouteStopRow to = stops.get(toIndex);
        LocalTime departureTime = from.getDepartureTime() != null
                ? from.getDepartureTime() : from.getArrivalTime();
        LocalTime arrivalTime = to.getArrivalTime() != null
                ? to.getArrivalTime() : to.getDepartureTime();
        if (departureTime == null || arrivalTime == null
                || from.getMileage() == null
                || to.getMileage() == null) {
            return null;
        }
        BigDecimal[][] seatPrices = new BigDecimal[3][1];
        boolean hasPrice = false;
        Map<Integer, Map<Integer, BigDecimal>> prices =
                priceByTrainSeat.getOrDefault(
                        from.getTrainId(), Map.of());
        for (int seatType = 1; seatType <= 3; seatType++) {
            BigDecimal total = BigDecimal.ZERO;
            boolean complete = true;
            Map<Integer, BigDecimal> segmentPrices =
                    prices.getOrDefault(seatType, Map.of());
            for (int segment = from.getStopOrder();
                 segment <= to.getStopOrder() - 1;
                 segment++) {
                BigDecimal segmentPrice = segmentPrices.get(segment);
                if (segmentPrice == null) {
                    complete = false;
                    break;
                }
                total = total.add(segmentPrice);
            }
            seatPrices[seatType - 1][0] = complete ? total : null;
            hasPrice |= complete;
        }
        if (!hasPrice) {
            return null;
        }
        LocalDateTime depart = LocalDateTime.of(
                DURATION_BASE.plusDays(from.getDayOffset()),
                departureTime);
        LocalDateTime arrive = LocalDateTime.of(
                DURATION_BASE.plusDays(to.getDayOffset()),
                arrivalTime);
        int duration = Math.max(0, Math.toIntExact(
                Duration.between(depart, arrive).toMinutes()));
        BigDecimal mileage = to.getMileage()
                .subtract(from.getMileage());
        return new MutableEntry(
                from.getTrainId(),
                from.getTrainNo(),
                from.getTrainType(),
                from.getStationId(),
                to.getStationId(),
                from.getStationName(),
                to.getStationName(),
                from.getStopOrder(),
                to.getStopOrder() - 1,
                departureTime,
                arrivalTime,
                from.getDayOffset() * 1440
                        + departureTime.getHour() * 60
                        + departureTime.getMinute(),
                duration,
                mileage,
                new BigDecimal[]{
                        seatPrices[0][0],
                        seatPrices[1][0],
                        seatPrices[2][0]
                });
    }

    private RouteBucket toBucket(RouteKey key,
                                 List<MutableEntry> entries) {
        int size = entries.size();
        long[] trainIds = new long[size];
        String[] trainNos = new String[size];
        String[] trainTypes = new String[size];
        String[] fromNames = new String[size];
        String[] toNames = new String[size];
        int[] fromSegments = new int[size];
        int[] toSegments = new int[size];
        LocalTime[] departTimes = new LocalTime[size];
        LocalTime[] arriveTimes = new LocalTime[size];
        int[] durations = new int[size];
        BigDecimal[] mileages = new BigDecimal[size];
        BigDecimal[][] prices = new BigDecimal[size][3];
        for (int i = 0; i < size; i++) {
            MutableEntry entry = entries.get(i);
            trainIds[i] = entry.trainId;
            trainNos[i] = entry.trainNo;
            trainTypes[i] = entry.trainType;
            fromNames[i] = entry.fromStationName;
            toNames[i] = entry.toStationName;
            fromSegments[i] = entry.fromSegmentIndex;
            toSegments[i] = entry.toSegmentIndex;
            departTimes[i] = entry.departTime;
            arriveTimes[i] = entry.arriveTime;
            durations[i] = entry.durationMinutes;
            mileages[i] = entry.mileage;
            prices[i] = entry.prices;
        }
        return new RouteBucket(
                key.fromStationId(), key.toStationId(),
                trainIds, trainNos, trainTypes,
                fromNames, toNames,
                fromSegments, toSegments,
                departTimes, arriveTimes,
                durations, mileages, prices);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public enum State {
        NEW,
        REBUILDING,
        READY,
        UNAVAILABLE
    }

    private record RouteKey(long fromStationId, long toStationId) {
    }

    private record Snapshot(long version,
                            LocalDateTime builtAt,
                            Map<RouteKey, RouteBucket> routes,
                            int relationCount) {
    }

    private record MutableEntry(long trainId,
                                String trainNo,
                                String trainType,
                                long fromStationId,
                                long toStationId,
                                String fromStationName,
                                String toStationName,
                                int fromSegmentIndex,
                                int toSegmentIndex,
                                LocalTime departTime,
                                LocalTime arriveTime,
                                int departMinuteOfWeek,
                                int durationMinutes,
                                BigDecimal mileage,
                                BigDecimal[] prices) {
    }
}
