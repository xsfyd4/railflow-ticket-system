package com.ticket.module.ticket;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 一个起终站对对应的车次平行数组。
 *
 * <p>所有数组的同一个下标描述同一趟车。价格数组按席别 1、2、3
 * 固定在列方向存放，不支持或配置缺失的席别为 null。
 */
public final class RouteBucket {

    private final long fromStationId;
    private final long toStationId;
    private final long[] trainIds;
    private final String[] trainNos;
    private final String[] trainTypes;
    private final String[] fromStationNames;
    private final String[] toStationNames;
    private final int[] fromSegmentIndexes;
    private final int[] toSegmentIndexes;
    private final LocalTime[] departTimes;
    private final LocalTime[] arriveTimes;
    private final int[] durationMinutes;
    private final BigDecimal[] mileages;
    private final BigDecimal[][] prices;

    public RouteBucket(long fromStationId,
                       long toStationId,
                       long[] trainIds,
                       String[] trainNos,
                       String[] trainTypes,
                       String[] fromStationNames,
                       String[] toStationNames,
                       int[] fromSegmentIndexes,
                       int[] toSegmentIndexes,
                       LocalTime[] departTimes,
                       LocalTime[] arriveTimes,
                       int[] durationMinutes,
                       BigDecimal[] mileages,
                       BigDecimal[][] prices) {
        this.fromStationId = fromStationId;
        this.toStationId = toStationId;
        this.trainIds = trainIds;
        this.trainNos = trainNos;
        this.trainTypes = trainTypes;
        this.fromStationNames = fromStationNames;
        this.toStationNames = toStationNames;
        this.fromSegmentIndexes = fromSegmentIndexes;
        this.toSegmentIndexes = toSegmentIndexes;
        this.departTimes = departTimes;
        this.arriveTimes = arriveTimes;
        this.durationMinutes = durationMinutes;
        this.mileages = mileages;
        this.prices = prices;
    }

    public int size() {
        return trainIds.length;
    }

    public long getFromStationId() {
        return fromStationId;
    }

    public long getToStationId() {
        return toStationId;
    }

    public long[] getTrainIds() {
        return trainIds;
    }

    public String[] getTrainNos() {
        return trainNos;
    }

    public String[] getTrainTypes() {
        return trainTypes;
    }

    public String[] getFromStationNames() {
        return fromStationNames;
    }

    public String[] getToStationNames() {
        return toStationNames;
    }

    public int[] getFromSegmentIndexes() {
        return fromSegmentIndexes;
    }

    public int[] getToSegmentIndexes() {
        return toSegmentIndexes;
    }

    public LocalTime[] getDepartTimes() {
        return departTimes;
    }

    public LocalTime[] getArriveTimes() {
        return arriveTimes;
    }

    public int[] getDurationMinutes() {
        return durationMinutes;
    }

    public BigDecimal[] getMileages() {
        return mileages;
    }

    public BigDecimal[][] getPrices() {
        return prices;
    }
}
