package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.order.entity.PayLog;
import com.ticket.module.order.entity.TicketOrder;
import com.ticket.module.order.mapper.OrderMapper;
import com.ticket.module.order.mapper.PayLogMapper;
import com.ticket.module.order.vo.OrderActionResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 支付的事务边界。
 *
 * <p>为什么支付要拆成“建流水”和“完成支付”两个事务？
 * <ol>
 *   <li><b>openAttempt</b> 用 {@code REQUIRES_NEW} 先抢占唯一幂等键。
 *       同一个 {@code requestId} 并发进来时，数据库唯一索引只会放行一个；
 *       其余请求拿到 DuplicateKeyException 后去读已存在的流水并重放结果。
 *       这一步必须在订单行锁之外，否则两个请求会堵在订单锁上，
 *       等到拿到锁时才发现幂等键已经重复，事务已经被标记回滚。</li>
 *   <li><b>complete</b> 在一个事务里完成“锁订单 → 条件改状态 → 流水置成功”。
 *       订单和流水要么一起成功，要么一起回滚，不会出现
 *       “订单已支付但流水仍是处理中”的脏数据。</li>
 * </ol>
 *
 * <p>当前 {@code channel=MOCK} 是模拟渠道，不真正调用第三方。
 * 接真实支付时，把 {@link #externalTxnId} 换成渠道返回的流水号即可；
 * 第三方异步回调再来时必须先按 {@code external_txn_id} 唯一索引去重。
 */
@Service
public class OrderPayTxService {

    private static final int ORDER_STATUS_PENDING_PAYMENT = 0;
    private static final int ORDER_STATUS_PAID = 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter PAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final OrderMapper orderMapper;
    private final PayLogMapper payLogMapper;

    public OrderPayTxService(OrderMapper orderMapper,
                             PayLogMapper payLogMapper) {
        this.orderMapper = orderMapper;
        this.payLogMapper = payLogMapper;
    }

    /**
     * 在独立事务里创建一条“处理中”的支付流水，抢占幂等键。
     *
     * @throws org.springframework.dao.DuplicateKeyException 幂等键已存在
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public PayLog openAttempt(Long userId,
                              String orderNo,
                              String requestId,
                              String channel) {
        TicketOrder order = orderMapper.findByOrderNoAndUser(orderNo, userId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }

        PayLog payLog = new PayLog();
        payLog.setPayNo(newPayNo());
        payLog.setRequestId(requestId);
        payLog.setOrderId(order.getId());
        payLog.setOrderNo(order.getOrderNo());
        payLog.setUserId(userId);
        payLog.setAmount(order.getTotalAmount());
        payLog.setChannel(channel);
        payLog.setStatus(PayLog.STATUS_PROCESSING);
        payLogMapper.insert(payLog);
        return payLog;
    }

    /**
     * 完成支付：锁订单、校验状态、条件更新、流水置成功。
     *
     * <p>如果订单已经被另一条流水支付成功，本次流水会被置为“已关闭”，
     * 并返回首次成功的流水号。调用方不会觉得失败，也不会被再扣一次钱。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderActionResultVO complete(PayLog payLog) {
        TicketOrder order = orderMapper.findByOrderNoAndUserForUpdate(
                payLog.getOrderNo(), payLog.getUserId());
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }

        if (order.getStatus() == ORDER_STATUS_PAID) {
            payLogMapper.finishWithoutPayment(payLog.getId(),
                    PayLog.STATUS_CLOSED,
                    "订单已支付，本次请求按幂等关闭，不会重复扣款");
            PayLog success = payLogMapper.findSuccessByOrderId(order.getId());
            return buildResult(order, success, true);
        }

        if (order.getStatus() != ORDER_STATUS_PENDING_PAYMENT) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }

        LocalDateTime now = LocalDateTime.now();
        if (order.getExpireTime() == null
                || !order.getExpireTime().isAfter(now)) {
            throw new BizException(ResultCode.ORDER_EXPIRED);
        }

        // 第二道并发保护：即使行锁失效，条件更新也只能成功一次。
        int affected = orderMapper.markPaid(order.getId(), now);
        if (affected != 1) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }

        String externalTxnId = "MOCK-" + payLog.getPayNo();
        int logAffected = payLogMapper.markSuccess(
                payLog.getId(), externalTxnId, now);
        if (logAffected != 1) {
            throw new BizException(ResultCode.PAY_LOG_STATE_ERROR);
        }

        payLog.setStatus(PayLog.STATUS_SUCCESS);
        payLog.setExternalTxnId(externalTxnId);
        payLog.setPaidAt(now);
        return buildResult(order, payLog, false);
    }

    /** 业务失败时把流水置为失败。独立事务，确保异常回滚不会把原因一起抹掉。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void fail(Long payLogId, int status, String reason) {
        payLogMapper.finishWithoutPayment(payLogId, status, abbreviate(reason));
    }

    private OrderActionResultVO buildResult(TicketOrder order,
                                            PayLog successLog,
                                            boolean replay) {
        OrderActionResultVO result = new OrderActionResultVO();
        result.setOrderNo(order.getOrderNo());
        result.setStatus(ORDER_STATUS_PAID);
        result.setPaidAt(successLog != null && successLog.getPaidAt() != null
                ? successLog.getPaidAt() : order.getPaidAt());
        result.setPayNo(successLog == null ? null : successLog.getPayNo());
        result.setChannel(successLog == null ? null : successLog.getChannel());
        result.setIdempotentReplay(replay);
        return result;
    }

    private static String newPayNo() {
        return "P" + LocalDateTime.now().format(PAY_TIME_FORMAT)
                + String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String abbreviate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "支付失败";
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}
