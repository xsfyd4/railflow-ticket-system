package com.ticket.module.ticket;

import com.ticket.common.result.PageResult;
import com.ticket.module.ticket.vo.TicketSeatVO;
import com.ticket.module.ticket.vo.TicketVO;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一页查票结果的紧凑缓存对象。
 *
 * <p>所有数组的同一个下标描述同一趟车。例如 trainIds[0]、
 * trainNos[0]、remainCounts[0] 都属于第一趟车。整页结果只占一个 Redis Key，
 * 一次 GET 就能返回前端需要的全部数据。
 */
public class TicketSearchSnapshot {

    private long total;
    private long page;
    private long size;
    private Long[] trainIds;
    private String[] trainNos;
    private String[] trainTypes;
    private String[] fromStationNames;
    private String[] toStationNames;
    private LocalTime[] departTimes;
    private LocalTime[] arriveTimes;
    private Integer[] durationMinutes;
    private BigDecimal[] mileages;
    private Integer[][] seatTypes;
    private Integer[][] remainCounts;
    private BigDecimal[][] prices;

    public static TicketSearchSnapshot from(PageResult<TicketVO> result) {
        List<TicketVO> records = result.getRecords() == null
                ? List.of() : result.getRecords();
        int count = records.size();
        TicketSearchSnapshot snapshot = new TicketSearchSnapshot();
        snapshot.total = result.getTotal();
        snapshot.page = result.getCurrent();
        snapshot.size = result.getSize();
        snapshot.trainIds = new Long[count];
        snapshot.trainNos = new String[count];
        snapshot.trainTypes = new String[count];
        snapshot.fromStationNames = new String[count];
        snapshot.toStationNames = new String[count];
        snapshot.departTimes = new LocalTime[count];
        snapshot.arriveTimes = new LocalTime[count];
        snapshot.durationMinutes = new Integer[count];
        snapshot.mileages = new BigDecimal[count];
        snapshot.seatTypes = new Integer[count][];
        snapshot.remainCounts = new Integer[count][];
        snapshot.prices = new BigDecimal[count][];

        for (int i = 0; i < count; i++) {
            TicketVO ticket = records.get(i);
            snapshot.trainIds[i] = ticket.getTrainId();
            snapshot.trainNos[i] = ticket.getTrainNo();
            snapshot.trainTypes[i] = ticket.getTrainType();
            snapshot.fromStationNames[i] = ticket.getFromStationName();
            snapshot.toStationNames[i] = ticket.getToStationName();
            snapshot.departTimes[i] = ticket.getDepartTime();
            snapshot.arriveTimes[i] = ticket.getArriveTime();
            snapshot.durationMinutes[i] = ticket.getDurationMinutes();
            snapshot.mileages[i] = ticket.getMileage();

            List<TicketSeatVO> seats = ticket.getSeats() == null
                    ? List.of() : ticket.getSeats();
            snapshot.seatTypes[i] = new Integer[seats.size()];
            snapshot.remainCounts[i] = new Integer[seats.size()];
            snapshot.prices[i] = new BigDecimal[seats.size()];
            for (int j = 0; j < seats.size(); j++) {
                TicketSeatVO seat = seats.get(j);
                snapshot.seatTypes[i][j] = seat.getSeatType();
                snapshot.remainCounts[i][j] = seat.getRemainCount();
                snapshot.prices[i][j] = seat.getPrice();
            }
        }
        return snapshot;
    }

    public PageResult<TicketVO> toPageResult() {
        validate();
        List<TicketVO> records = new ArrayList<>(trainIds.length);
        for (int i = 0; i < trainIds.length; i++) {
            TicketVO ticket = new TicketVO();
            ticket.setTrainId(trainIds[i]);
            ticket.setTrainNo(trainNos[i]);
            ticket.setTrainType(trainTypes[i]);
            ticket.setFromStationName(fromStationNames[i]);
            ticket.setToStationName(toStationNames[i]);
            ticket.setDepartTime(departTimes[i]);
            ticket.setArriveTime(arriveTimes[i]);
            ticket.setDurationMinutes(durationMinutes[i]);
            ticket.setMileage(mileages[i]);

            List<Integer> types = list(seatTypes[i]);
            List<Integer> remains = list(remainCounts[i]);
            List<BigDecimal> priceList = list(prices[i]);
            if (types.size() != remains.size()
                    || types.size() != priceList.size()) {
                throw new IllegalStateException("查票快照席别数组长度不一致");
            }
            List<TicketSeatVO> seats = new ArrayList<>(types.size());
            for (int j = 0; j < types.size(); j++) {
                seats.add(new TicketSeatVO(
                        types.get(j), remains.get(j), priceList.get(j)));
            }
            ticket.setSeats(seats);
            records.add(ticket);
        }
        return PageResult.of(total, page, size, records);
    }

    private void validate() {
        int count = trainIds == null ? 0 : trainIds.length;
        boolean sameTrainLength = trainNos != null
                && trainNos.length == count
                && trainTypes != null
                && trainTypes.length == count
                && fromStationNames != null
                && fromStationNames.length == count
                && toStationNames != null
                && toStationNames.length == count
                && departTimes != null
                && departTimes.length == count
                && arriveTimes != null
                && arriveTimes.length == count
                && durationMinutes != null
                && durationMinutes.length == count
                && mileages != null
                && mileages.length == count
                && seatTypes != null
                && seatTypes.length == count
                && remainCounts != null
                && remainCounts.length == count
                && prices != null
                && prices.length == count;
        if (!sameTrainLength) {
            throw new IllegalStateException("查票快照数组长度不一致");
        }
    }

    private <T> List<T> list(T[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public Long[] getTrainIds() {
        return trainIds;
    }

    public void setTrainIds(Long[] trainIds) {
        this.trainIds = trainIds;
    }

    public String[] getTrainNos() {
        return trainNos;
    }

    public void setTrainNos(String[] trainNos) {
        this.trainNos = trainNos;
    }

    public String[] getTrainTypes() {
        return trainTypes;
    }

    public void setTrainTypes(String[] trainTypes) {
        this.trainTypes = trainTypes;
    }

    public String[] getFromStationNames() {
        return fromStationNames;
    }

    public void setFromStationNames(String[] fromStationNames) {
        this.fromStationNames = fromStationNames;
    }

    public String[] getToStationNames() {
        return toStationNames;
    }

    public void setToStationNames(String[] toStationNames) {
        this.toStationNames = toStationNames;
    }

    public LocalTime[] getDepartTimes() {
        return departTimes;
    }

    public void setDepartTimes(LocalTime[] departTimes) {
        this.departTimes = departTimes;
    }

    public LocalTime[] getArriveTimes() {
        return arriveTimes;
    }

    public void setArriveTimes(LocalTime[] arriveTimes) {
        this.arriveTimes = arriveTimes;
    }

    public Integer[] getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer[] durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public BigDecimal[] getMileages() {
        return mileages;
    }

    public void setMileages(BigDecimal[] mileages) {
        this.mileages = mileages;
    }

    public Integer[][] getSeatTypes() {
        return seatTypes;
    }

    public void setSeatTypes(Integer[][] seatTypes) {
        this.seatTypes = seatTypes;
    }

    public Integer[][] getRemainCounts() {
        return remainCounts;
    }

    public void setRemainCounts(Integer[][] remainCounts) {
        this.remainCounts = remainCounts;
    }

    public BigDecimal[][] getPrices() {
        return prices;
    }

    public void setPrices(BigDecimal[][] prices) {
        this.prices = prices;
    }
}
