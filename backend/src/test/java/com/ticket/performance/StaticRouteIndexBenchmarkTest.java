package com.ticket.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取全部停站并构建“车站对 → 车次”索引的手动基准。
 *
 * <pre>
 * mvn -Dbenchmark=true -Dtest=StaticRouteIndexBenchmarkTest test
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StaticRouteIndexBenchmarkTest {

    private static final int WARMUP = 20;
    private static final int ITERATIONS = 100;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @EnabledIfSystemProperty(named = "benchmark", matches = "true")
    void benchmarkQueryAndBuild() {
        for (int i = 0; i < WARMUP; i++) {
            buildIndex(queryStops());
        }

        List<Long> queryNanos = new ArrayList<>();
        List<Long> buildNanos = new ArrayList<>();
        List<Long> combinedNanos = new ArrayList<>();
        Map<Long, List<RouteTrain>> lastIndex = Map.of();

        for (int i = 0; i < ITERATIONS; i++) {
            long started = System.nanoTime();
            List<StopRow> rows = queryStops();
            long queried = System.nanoTime();
            Map<Long, List<RouteTrain>> index = buildIndex(rows);
            long finished = System.nanoTime();

            queryNanos.add(queried - started);
            buildNanos.add(finished - queried);
            combinedNanos.add(finished - started);
            lastIndex = index;
        }

        print("database-query", queryNanos);
        print("in-memory-build", buildNanos);
        print("query-and-build", combinedNanos);
        System.out.printf(
                "ROUTE_INDEX_SIZE routes=%d routeTrainRelations=%d%n",
                lastIndex.size(),
                lastIndex.values().stream().mapToInt(List::size).sum());
    }

    private List<StopRow> queryStops() {
        return jdbcTemplate.query("""
                SELECT train_id, stop_order, station_id,
                       arrival_time, departure_time, day_offset
                FROM train_stop
                ORDER BY train_id, stop_order
                """, (rs, rowNum) -> new StopRow(
                rs.getLong("train_id"),
                rs.getInt("stop_order"),
                rs.getLong("station_id"),
                rs.getTime("arrival_time") == null
                        ? null : rs.getTime("arrival_time").toLocalTime(),
                rs.getTime("departure_time") == null
                        ? null : rs.getTime("departure_time").toLocalTime(),
                rs.getInt("day_offset")));
    }

    private Map<Long, List<RouteTrain>> buildIndex(List<StopRow> rows) {
        Map<Long, List<StopRow>> byTrain = new HashMap<>();
        for (StopRow row : rows) {
            byTrain.computeIfAbsent(row.trainId(),
                    ignored -> new ArrayList<>()).add(row);
        }

        Map<Long, List<RouteTrain>> index = new HashMap<>();
        for (List<StopRow> stops : byTrain.values()) {
            for (int i = 0; i < stops.size(); i++) {
                StopRow from = stops.get(i);
                for (int j = i + 1; j < stops.size(); j++) {
                    StopRow to = stops.get(j);
                    long routeKey = ((long) from.stationId() << 32)
                            | (to.stationId() & 0xffffffffL);
                    index.computeIfAbsent(routeKey,
                            ignored -> new ArrayList<>())
                            .add(new RouteTrain(
                                    from.trainId(),
                                    i + 1,
                                    j,
                                    from.departureTime(),
                                    to.arrivalTime()));
                }
            }
        }
        return index;
    }

    private void print(String name, List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        System.out.printf(
                "ROUTE_INDEX_BENCHMARK stage=%s avg=%.3fms "
                        + "p50=%.3fms p95=%.3fms p99=%.3fms%n",
                name,
                sorted.stream().mapToLong(Long::longValue).average()
                        .orElse(0) / 1_000_000.0,
                percentileMs(sorted, 0.50),
                percentileMs(sorted, 0.95),
                percentileMs(sorted, 0.99));
    }

    private double percentileMs(List<Long> sorted, double percentile) {
        int index = Math.min(sorted.size() - 1,
                (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(Math.max(index, 0)) / 1_000_000.0;
    }

    private record StopRow(long trainId,
                           int stopOrder,
                           long stationId,
                           LocalTime arrivalTime,
                           LocalTime departureTime,
                           int dayOffset) {
    }

    private record RouteTrain(long trainId,
                              int fromSegmentIndex,
                              int toSegmentIndex,
                              LocalTime departureTime,
                              LocalTime arrivalTime) {
    }
}
