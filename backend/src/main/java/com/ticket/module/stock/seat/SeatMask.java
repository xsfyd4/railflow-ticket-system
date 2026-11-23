package com.ticket.module.stock.seat;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.stock.entity.TicketStock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 座位位图。座位编号对外从 1 开始，内部位下标从 0 开始。
 */
public final class SeatMask {

    private static final int BITS_PER_WORD = 64;

    private final long[] words;
    private final int seatCount;

    private SeatMask(long[] words, int seatCount) {
        if (seatCount < 0) {
            throw new IllegalArgumentException("seatCount must not be negative");
        }
        this.seatCount = seatCount;
        this.words = Arrays.copyOf(words, wordCount(seatCount));
        maskUnusedBits(this.words, seatCount);
    }

    public static SeatMask empty(int seatCount) {
        return new SeatMask(new long[wordCount(seatCount)], seatCount);
    }

    public static SeatMask full(int seatCount) {
        long[] words = new long[wordCount(seatCount)];
        Arrays.fill(words, -1L);
        return new SeatMask(words, seatCount);
    }

    public static SeatMask fromStock(TicketStock stock) {
        if (stock == null || stock.getTotalCount() == null) {
            throw new BizException(ResultCode.STOCK_DATA_ERROR);
        }
        int totalCount = stock.getTotalCount();
        byte[] bytes = stock.getSoldSeatMask();
        if (bytes != null && bytes.length > 0) {
            return fromBytes(bytes, totalCount);
        }

        /*
         * 兼容旧数据：旧表只有 sold_count / remaining_count，没有具体座位。
         * 对旧数据使用前 N 个座位作为已售位，第一次写库存时自动规范化为位图。
         */
        int soldCount = stock.getSoldCount() == null ? 0 : stock.getSoldCount();
        int remaining = stock.getRemainingCount() == null
                ? totalCount : stock.getRemainingCount();
        int effectiveSold = Math.max(soldCount, totalCount - remaining);
        return firstBits(totalCount, effectiveSold);
    }

    public static SeatMask fromBytes(byte[] bytes, int seatCount) {
        long[] words = new long[wordCount(seatCount)];
        int limit = Math.min(bytes.length, words.length * Long.BYTES);
        for (int i = 0; i < limit; i++) {
            int wordIndex = i / Long.BYTES;
            int shift = (i % Long.BYTES) * Byte.SIZE;
            words[wordIndex] |= (long) (bytes[i] & 0xff) << shift;
        }
        return new SeatMask(words, seatCount);
    }

    public static SeatMask ofSeatIndexes(int seatCount, List<Integer> seatIndexes) {
        SeatMask mask = empty(seatCount);
        for (Integer seatIndex : seatIndexes) {
            mask = mask.withSeat(seatIndex);
        }
        return mask;
    }

    private static SeatMask firstBits(int seatCount, int count) {
        long[] words = new long[wordCount(seatCount)];
        int remaining = Math.min(Math.max(0, count), seatCount);
        for (int wordIndex = 0; wordIndex < words.length && remaining > 0; wordIndex++) {
            int bits = Math.min(BITS_PER_WORD, remaining);
            words[wordIndex] = bits == BITS_PER_WORD
                    ? -1L : (1L << bits) - 1L;
            remaining -= bits;
        }
        return new SeatMask(words, seatCount);
    }

    public SeatMask withSeat(int seatIndex) {
        validateSeatIndex(seatIndex);
        long[] copy = Arrays.copyOf(words, words.length);
        int zeroBased = seatIndex - 1;
        copy[zeroBased / BITS_PER_WORD] |=
                1L << (zeroBased % BITS_PER_WORD);
        return new SeatMask(copy, seatCount);
    }

    public SeatMask withoutSeat(int seatIndex) {
        validateSeatIndex(seatIndex);
        long[] copy = Arrays.copyOf(words, words.length);
        int zeroBased = seatIndex - 1;
        copy[zeroBased / BITS_PER_WORD] &=
                ~(1L << (zeroBased % BITS_PER_WORD));
        return new SeatMask(copy, seatCount);
    }

    public SeatMask or(SeatMask other) {
        requireSameSeatCount(other);
        long[] result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = words[i] | other.words[i];
        }
        return new SeatMask(result, seatCount);
    }

    public SeatMask andNot(SeatMask other) {
        requireSameSeatCount(other);
        long[] result = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            result[i] = words[i] & ~other.words[i];
        }
        return new SeatMask(result, seatCount);
    }

    public int cardinality() {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }

    public List<Integer> firstSeatIndexes(int count) {
        if (count < 0 || count > cardinality()) {
            throw new BizException(ResultCode.TICKET_NOT_ENOUGH);
        }
        List<Integer> result = new ArrayList<>(count);
        for (int wordIndex = 0;
             wordIndex < words.length && result.size() < count;
             wordIndex++) {
            long word = words[wordIndex];
            while (word != 0L && result.size() < count) {
                int bit = Long.numberOfTrailingZeros(word);
                result.add(wordIndex * BITS_PER_WORD + bit + 1);
                word &= word - 1;
            }
        }
        return result;
    }

    public byte[] toBytes() {
        int byteCount = bytesForSeats(seatCount);
        byte[] bytes = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            int wordIndex = i / Long.BYTES;
            int shift = (i % Long.BYTES) * Byte.SIZE;
            bytes[i] = (byte) (words[wordIndex] >>> shift);
        }
        return bytes;
    }

    public int seatCount() {
        return seatCount;
    }

    private void requireSameSeatCount(SeatMask other) {
        if (seatCount != other.seatCount) {
            throw new BizException(ResultCode.STOCK_DATA_ERROR);
        }
    }

    private void validateSeatIndex(int seatIndex) {
        if (seatIndex < 1 || seatIndex > seatCount) {
            throw new BizException(ResultCode.STOCK_DATA_ERROR);
        }
    }

    private static int wordCount(int seatCount) {
        return seatCount <= 0 ? 0 : (seatCount + BITS_PER_WORD - 1) / BITS_PER_WORD;
    }

    private static int bytesForSeats(int seatCount) {
        return (seatCount + Byte.SIZE - 1) / Byte.SIZE;
    }

    private static void maskUnusedBits(long[] words, int seatCount) {
        if (words.length == 0) {
            return;
        }
        int used = seatCount % BITS_PER_WORD;
        if (used != 0) {
            words[words.length - 1] &= (1L << used) - 1L;
        }
    }
}
