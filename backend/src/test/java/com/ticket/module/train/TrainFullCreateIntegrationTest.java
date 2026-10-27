package com.ticket.module.train;

import com.ticket.common.exception.BizException;
import com.ticket.module.train.dto.StopsSaveDTO;
import com.ticket.module.train.dto.TrainFullCreateDTO;
import com.ticket.module.train.dto.TrainPriceItemDTO;
import com.ticket.module.train.dto.TrainSaveDTO;
import com.ticket.module.train.dto.TrainStopItemDTO;
import com.ticket.module.train.impl.TrainFullCreateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TrainFullCreateIntegrationTest {

    private static final String TRAIN_NO = "G9901";
    private static final String ORDER_NO = "TEST-FULL-CREATE-ORDER-0001";

    @Autowired
    private TrainFullCreateService fullCreateService;
    @Autowired
    private TrainService trainService;
    @Autowired
    private TrainStopService trainStopService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldCreateTrainStopsSegmentsAndPricesInOneTransaction() {
        Long trainId = fullCreateService.create(fullDto());

        assertEquals(3, count(
                "SELECT COUNT(*) FROM train_stop WHERE train_id = ?", trainId));
        assertEquals(2, count(
                "SELECT COUNT(*) FROM train_segment WHERE train_id = ?", trainId));
        assertEquals(6, count(
                "SELECT COUNT(*) FROM train_segment_price WHERE train_id = ?",
                trainId));
        assertEquals(0, new BigDecimal("1000.0").compareTo(
                jdbcTemplate.queryForObject(
                        "SELECT total_mileage FROM train WHERE id = ?",
                        BigDecimal.class, trainId)));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT start_station_id FROM train WHERE id = ?",
                Long.class, trainId));
        assertEquals(27L, jdbcTemplate.queryForObject(
                "SELECT end_station_id FROM train WHERE id = ?",
                Long.class, trainId));
    }

    @Test
    void shouldRejectChangingTrainEndpointsAwayFromExistingStops() {
        Long trainId = fullCreateService.create(fullDto());

        TrainSaveDTO changed = new TrainSaveDTO();
        changed.setTrainNo(TRAIN_NO);
        changed.setTrainType("G");
        changed.setStartStationId(2L);
        changed.setEndStationId(27L);
        changed.setTotalMileage(new BigDecimal("1000.0"));
        changed.setStatus(1);

        BizException error = assertThrows(BizException.class,
                () -> trainService.update(trainId, changed));
        assertEquals(400, error.getCode());
    }

    @Test
    void shouldRequireClearingStocksBeforeChangingStops() {
        Long trainId = fullCreateService.create(fullDto());
        jdbcTemplate.update("""
                INSERT INTO ticket_stock
                    (travel_date, train_id, segment_index, seat_type,
                     from_station_id, to_station_id,
                     total_count, sold_count, remaining_count)
                VALUES (?, ?, 1, 3, 1, 30, 10, 0, 10)
                """, LocalDate.of(2099, 9, 1), trainId);

        BizException blocked = assertThrows(BizException.class,
                () -> trainStopService.saveStops(trainId, stopsDto()));
        assertEquals(400, blocked.getCode());

        trainService.clearStocksForReconfiguration(trainId);
        trainStopService.saveStops(trainId, stopsDto());

        assertEquals(0, count(
                "SELECT COUNT(*) FROM ticket_stock WHERE train_id = ?", trainId));
        assertEquals(3, count(
                "SELECT COUNT(*) FROM train_stop WHERE train_id = ?", trainId));
        assertEquals(2, count(
                "SELECT COUNT(*) FROM train_segment WHERE train_id = ?", trainId));
    }

    @Test
    void shouldNeverAllowReconfiguringStopsAfterOrderExists() {
        Long trainId = fullCreateService.create(fullDto());
        jdbcTemplate.update("""
                INSERT INTO ticket_order
                    (order_no, request_id, user_id, train_id, train_no,
                     travel_date, from_station_id, to_station_id,
                     from_segment_index, to_segment_index, seat_type,
                     passenger_count, total_amount, status, expire_time)
                VALUES (?, NULL, 1, ?, ?, ?, 1, 27, 1, 2, 3, 1, 100.00, 0,
                        DATE_ADD(NOW(), INTERVAL 1 DAY))
                """, ORDER_NO, trainId, TRAIN_NO,
                LocalDate.of(2099, 9, 1));

        assertThrows(BizException.class,
                () -> trainService.clearStocksForReconfiguration(trainId));
        assertThrows(BizException.class,
                () -> trainStopService.saveStops(trainId, stopsDto()));
    }

    private TrainFullCreateDTO fullDto() {
        TrainFullCreateDTO dto = new TrainFullCreateDTO();
        dto.setTrainNo(TRAIN_NO);
        dto.setTrainType("G");
        dto.setStatus(1);
        dto.setRemark("完整创建测试");
        dto.setStops(List.of(
                stop(1L, null, LocalTime.of(8, 0), 0, "0"),
                stop(30L, LocalTime.of(10, 0), LocalTime.of(10, 5),
                        0, "500"),
                stop(27L, LocalTime.of(12, 0), null, 0, "1000")
        ));

        List<TrainPriceItemDTO> prices = new ArrayList<>();
        for (int segment = 1; segment <= 2; segment++) {
            for (int seat = 1; seat <= 3; seat++) {
                TrainPriceItemDTO price = new TrainPriceItemDTO();
                price.setSegmentIndex(segment);
                price.setSeatType(seat);
                price.setPrice(BigDecimal.valueOf(100L * seat + segment));
                prices.add(price);
            }
        }
        dto.setPrices(prices);
        return dto;
    }

    private StopsSaveDTO stopsDto() {
        StopsSaveDTO dto = new StopsSaveDTO();
        dto.setStops(List.of(
                stop(1L, null, LocalTime.of(8, 0), 0, "0"),
                stop(30L, LocalTime.of(10, 0), LocalTime.of(10, 5),
                        0, "500"),
                stop(27L, LocalTime.of(12, 0), null, 0, "1000")
        ));
        return dto;
    }

    private TrainStopItemDTO stop(Long stationId,
                                  LocalTime arrival,
                                  LocalTime departure,
                                  int offset,
                                  String mileage) {
        TrainStopItemDTO stop = new TrainStopItemDTO();
        stop.setStationId(stationId);
        stop.setArrivalTime(arrival);
        stop.setDepartureTime(departure);
        stop.setDayOffset(offset);
        stop.setMileage(new BigDecimal(mileage));
        return stop;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM ticket_order WHERE order_no = ?",
                ORDER_NO);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM train WHERE train_no = ?",
                Long.class, TRAIN_NO);
        for (Long id : ids) {
            jdbcTemplate.update("DELETE FROM ticket_stock WHERE train_id = ?", id);
            jdbcTemplate.update(
                    "DELETE FROM train_segment_price WHERE train_id = ?", id);
            jdbcTemplate.update(
                    "DELETE FROM train_segment WHERE train_id = ?", id);
            jdbcTemplate.update("DELETE FROM train_stop WHERE train_id = ?", id);
            jdbcTemplate.update("DELETE FROM train WHERE id = ?", id);
        }
    }
}
