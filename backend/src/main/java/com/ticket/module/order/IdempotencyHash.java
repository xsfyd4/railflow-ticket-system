package com.ticket.module.order;

import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.dto.PayOrderDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 把一次请求转换成稳定摘要。
 *
 * <p>幂等键只回答“是不是同一个操作”，摘要回答“业务参数是否真的相同”。
 * 乘车人 id 必须先排序，否则 [1,2] 和 [2,1] 会被误判成两个请求。
 * 字段采用“长度 + 内容”编码，避免普通分隔符拼接产生碰撞。
 */
public final class IdempotencyHash {

    private IdempotencyHash() {
    }

    public static String createOrder(Long userId, CreateOrderDTO dto) {
        List<Long> passengerIds = new ArrayList<>(dto.getPassengerIds());
        Collections.sort(passengerIds);
        List<String> fields = new ArrayList<>();
        fields.add(String.valueOf(userId));
        fields.add(String.valueOf(dto.getTrainId()));
        fields.add(String.valueOf(dto.getTravelDate()));
        fields.add(String.valueOf(dto.getFromStationId()));
        fields.add(String.valueOf(dto.getToStationId()));
        fields.add(String.valueOf(dto.getSeatType()));
        fields.add(String.valueOf(passengerIds.size()));
        for (Long passengerId : passengerIds) {
            fields.add(String.valueOf(passengerId));
        }
        return sha256(fields);
    }

    public static String payOrder(Long userId,
                                  String orderNo,
                                  String channel) {
        return sha256(List.of(
                String.valueOf(userId),
                orderNo,
                channel));
    }

    private static String sha256(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(String.valueOf(bytes.length)
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) ';');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
