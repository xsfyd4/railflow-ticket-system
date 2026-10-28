package com.ticket.module.order.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.PageResult;
import com.ticket.common.result.ResultCode;
import com.ticket.module.order.OrderService;
import com.ticket.module.order.IdempotencyHash;
import com.ticket.module.order.RedisIdempotencyStore;
import com.ticket.module.order.dto.CreateOrderDTO;
import com.ticket.module.order.dto.OrderQueryDTO;
import com.ticket.module.order.dto.PayOrderDTO;
import com.ticket.module.order.dto.RefundOrderDTO;
import com.ticket.module.order.entity.PayLog;
import com.ticket.module.order.entity.TicketOrder;
import com.ticket.module.order.entity.TicketOrderItem;
import com.ticket.module.order.mapper.OrderMapper;
import com.ticket.module.order.mapper.PayLogMapper;
import com.ticket.module.order.vo.CreateOrderVO;
import com.ticket.module.order.vo.OrderActionResultVO;
import com.ticket.module.order.vo.OrderDetailVO;
import com.ticket.module.order.vo.OrderItemVO;
import com.ticket.module.order.vo.OrderRouteVO;
import com.ticket.module.order.vo.OrderVO;
import com.ticket.module.order.vo.PayLogVO;
import com.ticket.module.order.vo.RefundResultVO;
import com.ticket.module.passenger.entity.Passenger;
import com.ticket.module.passenger.mapper.PassengerMapper;
import com.ticket.module.stock.TicketStockCache;
import com.ticket.module.ticket.TicketCacheInvalidator;
import com.ticket.module.train.vo.TrainPriceVO;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 下单业务实现：把“查票结果”转换成一张真正占用库存的待支付订单。
 *
 * <h2>一次下单会写哪些数据？</h2>
 * <ol>
 *   <li>修改 {@code ticket_stock}：扣减出发站到到达站覆盖的每一段库存。</li>
 *   <li>写 {@code ticket_order}：订单主表，一位用户一次出行对应一行。</li>
 *   <li>写 {@code ticket_order_item}：一位乘车人一行，保存购票时的姓名和证件号快照。</li>
 * </ol>
 *
 * <p>订单的起止段区间保存在 {@code ticket_order.from_segment_index} 和
 * {@code ticket_order.to_segment_index}。当前所有乘客共享同一次行程区间，
 * 因此不再按“每张票 × 每一段”展开存储，避免无效的数据膨胀。
 *
 * <h2>为什么整个方法必须在一个事务里？</h2>
 * <p>假如买了北京南到上海虹桥的票，覆盖 4 个分段。如果只成功扣了前 3 段，
 * 第 4 段余票不足，就会产生“库存被扣了但订单没创建成功”的脏数据。
 * 加上 {@link Transactional} 后，方法中途抛出异常时，MySQL 会撤销本次事务里的全部修改。
 *
 * <h2>为什么前端不能传金额和段号？</h2>
 * <p>前端传的数据都不可信。攻击者可以篡改 JSON，例如把 553 元改成 1 元，
 * 或者伪造段号只扣一段库存。因此站序、段号、价格和总金额都必须由后端重新查询和计算。
 */
@Service
public class OrderServiceImpl implements OrderService {

    /** 订单主表状态：0=待支付。提交订单时只扣库存，并不代表已经付款成功。 */
    private static final int STATUS_PENDING_PAYMENT = 0;

    /** 订单主表状态：1=已支付。 */
    private static final int STATUS_PAID = 1;

    /** 订单主表状态：2=已取消。 */
    private static final int STATUS_CANCELLED = 2;

    /** 订单主表状态：3=部分退票。 */
    private static final int STATUS_PARTIALLY_REFUNDED = 3;

    /** 订单主表状态：4=全部退票。 */
    private static final int STATUS_FULLY_REFUNDED = 4;

    /** 订单明细状态：0=待出票。支付成功后才会进入后续出票流程。 */
    private static final int ITEM_PENDING_TICKET = 0;

    /** 创建订单后给用户 15 分钟支付时间。超时释放库存要由定时任务另外完成。 */
    private static final int PAYMENT_TIMEOUT_MINUTES = 15;

    /**
     * 生成订单号时使用的安全随机数。
     *
     * <p>时间部分只精确到毫秒，同一毫秒内仍可能有两个请求，
     * 所以后面再加 9 位随机数字。数据库的 order_no 唯一索引是最终兜底。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 订单号前半部分格式：年月日时分秒 + 3 位毫秒，共 17 位。 */
    private static final DateTimeFormatter ORDER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 订单模块的 Mapper：查路线、查价格、扣库存、写订单主表和明细表。 */
    private final OrderMapper orderMapper;

    /** 乘车人 Mapper：确认乘车人属于当前登录用户，并取得姓名和证件号快照。 */
    private final PassengerMapper passengerMapper;

    /** 支付流水 Mapper：支付幂等重放与流水查询。 */
    private final PayLogMapper payLogMapper;

    /** 支付事务边界：先建流水抢占幂等键，再在独立事务里完成扣款状态流转。 */
    private final OrderPayTxService payTxService;

    /** 订单改变库存后，在事务提交后失效查票缓存。 */
    private final TicketCacheInvalidator cacheInvalidator;

    /** 同步 HTTP 下单成功后，把 Redis 数字库存同步减掉。 */
    private final TicketStockCache stockCache;
    private final RedisIdempotencyStore idempotencyStore;

    /**
     * 构造器注入。
     *
     * <p>Spring 创建 OrderServiceImpl 时，会把两个 Mapper 的实现对象传进来，
     * 不需要我们自己 new。这样 Service 只关注业务，不关心数据库连接怎么建立。
     */
    public OrderServiceImpl(OrderMapper orderMapper,
                            PassengerMapper passengerMapper,
                            PayLogMapper payLogMapper,
                            OrderPayTxService payTxService,
                            TicketCacheInvalidator cacheInvalidator,
                            TicketStockCache stockCache,
                            RedisIdempotencyStore idempotencyStore) {
        this.orderMapper = orderMapper;
        this.passengerMapper = passengerMapper;
        this.payLogMapper = payLogMapper;
        this.payTxService = payTxService;
        this.cacheInvalidator = cacheInvalidator;
        this.stockCache = stockCache;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * 创建一张待支付订单。
     *
     * @param dto 前端提交的车次、日期、起止站、席别和乘车人 id
     * @return 订单主键、业务订单号、总金额、状态和支付截止时间
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateOrderVO create(CreateOrderDTO dto) {
        return createInternal(currentUserId(), dto, true, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateOrderVO createWithRequestId(CreateOrderDTO dto,
                                             String requestId) {
        return createInternal(currentUserId(), dto, true, requestId);
    }

    /**
     * MQ 消费者没有浏览器请求线程，因此不能从 ThreadLocal 取用户。
     * 这里的 userId 来自已经持久化的 order_request，库存已在投递 MQ 前由 Redis 预扣。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateOrderVO createForUser(Long userId, CreateOrderDTO dto) {
        return createInternal(userId, dto, false, null);
    }

    private CreateOrderVO createInternal(Long userId,
                                         CreateOrderDTO dto,
                                         boolean syncRedisStock,
                                         String requestId) {
        // 同一车站无法形成一段旅程，提前拒绝可以避免后面出现 0 段或反向段。
        if (dto.getFromStationId().equals(dto.getToStationId())) {
            throw new BizException(400, "出发站和到达站不能相同");
        }

        /*
         * 检查同一个乘车人是否在本次请求里出现两次。
         * 例如 List 是 [10, 10, 11]：原长度 3，HashSet 去重后长度 2，
         * 两个长度不一致说明有重复 id，不能让同一个人在同一订单里重复买票。
         */
        if (new HashSet<>(dto.getPassengerIds()).size()
                != dto.getPassengerIds().size()) {
            throw new BizException(400, "同一个乘车人不能重复购票");
        }

        /*
         * 第 1 步：一次校验全部乘车人。
         *
         * SQL 的 WHERE 同时包含 user_id，因此即使有人伪造别人的乘车人 id，
         * 查询结果也会少一条，随后被数量校验拒绝。最多 5 人，IN 查询足够轻量。
         */
        List<Passenger> foundPassengers =
                passengerMapper.findByIdsAndUserId(
                        dto.getPassengerIds(), userId);
        if (foundPassengers.size() != dto.getPassengerIds().size()) {
            throw new BizException(ResultCode.PASSENGER_NOT_FOUND);
        }

        // 恢复成前端提交的乘车人顺序，保证订单明细展示顺序稳定。
        java.util.Map<Long, Passenger> passengerById =
                new java.util.HashMap<>();
        for (Passenger passenger : foundPassengers) {
            passengerById.put(passenger.getId(), passenger);
        }
        List<Passenger> passengers =
                new ArrayList<>(dto.getPassengerIds().size());
        for (Long passengerId : dto.getPassengerIds()) {
            Passenger passenger = passengerById.get(passengerId);
            if (passenger == null) {
                throw new BizException(ResultCode.PASSENGER_NOT_FOUND);
            }
            passengers.add(passenger);
        }

        /*
         * 第 2 步：确认这趟车是否真的从出发站开到到达站。
         *
         * SQL 会同时关联出发站和到达站两条 train_stop 记录，并要求：
         * from_stop_order < to_stop_order
         * 所以反向查询、站点不属于该车次时都返回 null。
         */
        OrderRouteVO route = orderMapper.findRoute(
                dto.getTrainId(),
                dto.getFromStationId(),
                dto.getToStationId());
        if (route == null) {
            throw new BizException(ResultCode.TRAIN_NOT_FOUND);
        }

        /*
         * train_segment 的编号规则：
         * 第 k 段 = 第 k 站 -> 第 k+1 站。
         *
         * 因此从第 fromStopOrder 站出发，第一段就是 fromStopOrder；
         * 到第 toStopOrder 站结束，最后覆盖的段是 toStopOrder - 1。
         *
         * 例：从第 2 站到第 5 站，购买的是第 2、3、4 段，共 3 段。
         */
        int fromSegmentIndex = route.getFromStopOrder();
        int toSegmentIndex = route.getToStopOrder() - 1;
        int segmentCount = toSegmentIndex - fromSegmentIndex + 1;

        // SQL 已经做了方向校验，这里再兜底一次，防止错误的静态数据进入扣库存逻辑。
        if (segmentCount < 1) {
            throw new BizException(ResultCode.TRAIN_NOT_FOUND);
        }

        /*
         * 第 3 步：由后端查询每一段、当前席别的价格。
         * 前端根本没有传金额，因此不存在“前端篡改价格”的入口。
         */
        List<TrainPriceVO> prices = orderMapper.findPrices(
                dto.getTrainId(),
                dto.getSeatType(),
                fromSegmentIndex,
                toSegmentIndex);

        // 把每段价格相加，得到“一位乘车人”的票价。
        BigDecimal pricePerPassenger =
                calculatePrice(prices, fromSegmentIndex, segmentCount);

        /*
         * 第 4 步：一次性条件扣减区间内的所有分段库存。
         *
         * 对应 SQL 的关键条件：
         *     remaining_count >= 购买人数
         *
         * 数据库在执行 UPDATE 时会对符合条件的库存行加锁。
         * 如果两个请求同时抢最后一张票，第一个请求先扣成功并提交；
         * 第二个请求等到锁释放后重新判断，发现余票已经不满足条件，
         * 于是该行影响行数为 0，不会卖成负数。
         *
         * affected 是实际更新的库存行数。覆盖 4 段时，正常必须等于 4。
         */
        int affected = orderMapper.deductStock(
                dto.getTravelDate(),
                dto.getTrainId(),
                dto.getSeatType(),
                fromSegmentIndex,
                toSegmentIndex,
                passengers.size());

        /*
         * 只要有一段没有扣成功，就说明该段余票不足。
         * 注意：前面的其他段可能已经在本次事务里被 UPDATE 了，
         * 但这里抛出异常后，@Transactional 会撤销本次事务的全部库存修改。
         */
        if (affected != segmentCount) {
            throw new BizException(ResultCode.TICKET_NOT_ENOUGH);
        }

        /*
         * 第 5 步：库存预占成功后，落订单主表和订单明细。
         *
         * 整个方法共用同一个 now，保证订单号和 expireTime 基于同一时刻生成。
         */
        LocalDateTime now = LocalDateTime.now();

        // 组装订单主表对象。此时对象只在 Java 内存中，还没有写入数据库。
        TicketOrder order = buildOrder(
                dto, route, userId, passengers.size(),
                fromSegmentIndex, toSegmentIndex,
                pricePerPassenger, now, requestId);

        /*
         * 插入 ticket_order。
         * XML 中配置了 useGeneratedKeys=true、keyProperty=id，
         * 所以 MySQL 生成的订单主键会自动回填到 order.getId()，
         * 后面的订单明细才能关联到这张订单。
         */
        orderMapper.insertOrder(order);

        // 每一位乘车人对应一条 ticket_order_item，最后一次性批量插入。
        List<TicketOrderItem> items =
                new ArrayList<>(passengers.size());
        for (Passenger passenger : passengers) {
            items.add(buildItem(
                    order.getId(), dto.getSeatType(),
                    pricePerPassenger, passenger));
        }
        orderMapper.batchInsertItems(items);

        cacheInvalidator.invalidateDateAfterCommit(dto.getTravelDate());

        if (syncRedisStock) {
            /*
             * 同步 HTTP 下单没有先去 Redis 抢预扣名额，因此数据库成功扣减后
             * 要把数字库存同步减掉，保证之后走异步下单的请求看到同一份可售量。
             * 异步消费者不执行这里：它在进入 MQ 前已经扣过 Redis。
             */
            stockCache.decrementAfterCommit(
                    dto.getTravelDate(),
                    dto.getTrainId(),
                    dto.getSeatType(),
                    fromSegmentIndex,
                    toSegmentIndex,
                    passengers.size());
        }

        // 到这里库存和两张订单表都写成功，返回前端页面需要的下单结果。
        return toCreateOrderVO(order);
    }

    @Override
    public PageResult<OrderVO> page(OrderQueryDTO query) {
        Long userId = currentUserId();

        // 和车站、车次列表一样，分页参数必须兜底，防止一次拉取整张订单表。
        int page = query.getPage() == null || query.getPage() < 1
                ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1
                ? 10 : query.getSize();
        if (size > 50) {
            size = 50;
        }

        long total = orderMapper.countByUser(userId, query.getStatus());
        int offset = (page - 1) * size;
        List<OrderVO> records = orderMapper.findPageByUser(
                userId, query.getStatus(), offset, size);
        return PageResult.of(total, page, size, records);
    }

    @Override
    public OrderDetailVO detail(String orderNo) {
        Long userId = currentUserId();

        // user_id 和 order_no 一起查询，用户即使拿到别人的订单号也看不到订单。
        OrderDetailVO order =
                orderMapper.findDetailByUserAndNo(userId, orderNo);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }

        List<OrderItemVO> items =
                orderMapper.findItemsByOrderId(order.getId());
        for (OrderItemVO item : items) {
            item.setIdCardNo(OrderItemVO.maskIdCard(item.getIdCardNo()));
        }
        order.setItems(items);
        return order;
    }

    /**
     * 支付（幂等）。
     *
     * <p>流程分三段：
     * <ol>
     *   <li>解析幂等键，先在独立事务里插入 {@code pay_log}，用唯一索引抢占；</li>
     *   <li>事务内锁订单、条件更新为已支付，并把流水置为成功；</li>
     *   <li>同一幂等键的重复请求不再扣款，而是重放第一次的结果。</li>
     * </ol>
     *
     * <p>注意这里故意不加 {@code @Transactional}：建流水必须是独立事务，
     * 否则唯一键冲突会把外层事务标记为 rollback-only，后面的重放查询也没法继续。
     */
    @Override
    public OrderActionResultVO pay(String orderNo,
                                   String idempotencyKey,
                                   PayOrderDTO dto) {
        Long userId = currentUserId();
        String requestId = resolveRequestId(idempotencyKey, dto, userId, orderNo);
        String channel = resolveChannel(dto);
        String requestHash = IdempotencyHash.payOrder(
                userId, orderNo, channel);

        PayLog existing = payLogMapper.findByRequestId(requestId);
        if (existing != null) {
            return replayExisting(userId, orderNo, requestId);
        }

        RedisIdempotencyStore.AcquireResult gate =
                idempotencyStore.acquireAfterDatabaseMiss(
                        "PAY", requestId, userId, requestHash);
        if (gate.status()
                == RedisIdempotencyStore.Status.CONFLICT) {
            throw new BizException(ResultCode.PAY_REQUEST_CONFLICT);
        }
        if (gate.status()
                == RedisIdempotencyStore.Status.PROCESSING) {
            throw new BizException(ResultCode.PAY_PROCESSING);
        }
        if (gate.status()
                == RedisIdempotencyStore.Status.REPLAY) {
            return replayExisting(userId, orderNo, requestId);
        }

        PayLog attempt;
        try {
            attempt = payTxService.openAttempt(
                    userId, orderNo, requestId, channel);
        } catch (DuplicateKeyException duplicate) {
            // 幂等键已存在：这次支付已经成功、失败，或正在处理中
            return replayExisting(userId, orderNo, requestId);
        }

        try {
            OrderActionResultVO result = payTxService.complete(attempt);
            idempotencyStore.markSuccess(
                    "PAY", requestId, gate.ownerToken(),
                    result.getPayNo());
            return result;
        } catch (RuntimeException e) {
            try {
                payTxService.fail(
                        attempt.getId(), PayLog.STATUS_FAILED, e.getMessage());
            } catch (RuntimeException ignore) {
                // 写失败原因本身失败时，不能掩盖原始业务异常
            }
            idempotencyStore.markFailed(
                    "PAY", requestId, gate.ownerToken(), e.getMessage());
            throw e;
        }
    }

    @Override
    public List<PayLogVO> payments(String orderNo) {
        Long userId = currentUserId();
        if (orderMapper.findByOrderNoAndUser(orderNo, userId) == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        return payLogMapper.findByOrderNoAndUser(orderNo, userId);
    }

    /**
     * 同一幂等键重复到达：读取已有流水并重放结果。
     *
     * <p>如果另一条请求还在处理中，最多等待 1 秒再返回“处理中”，
     * 这样并发重复点击在绝大多数情况下拿到的都是同一个成功结果。
     */
    private OrderActionResultVO replayExisting(Long userId,
                                               String orderNo,
                                               String requestId) {
        PayLog payLog = null;
        for (int i = 0; i < 20; i++) {
            payLog = payLogMapper.findByRequestId(requestId);
            if (payLog != null) {
                if (!userId.equals(payLog.getUserId())
                        || !orderNo.equals(payLog.getOrderNo())) {
                    throw new BizException(ResultCode.PAY_REQUEST_CONFLICT);
                }
                if (payLog.getStatus() != PayLog.STATUS_PROCESSING) {
                    return replayResult(payLog);
                }
            }
            sleepQuietly(50L);
        }
        throw new BizException(ResultCode.PAY_PROCESSING);
    }

    private OrderActionResultVO replayResult(PayLog payLog) {
        TicketOrder order = orderMapper.findByOrderNoAndUser(
                payLog.getOrderNo(), payLog.getUserId());
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }

        if (payLog.getStatus() == PayLog.STATUS_SUCCESS) {
            return buildPayResult(order, payLog, true);
        }

        // 本次尝试失败/关闭，但订单可能已经被另一条流水支付成功
        if (order.getStatus() == STATUS_PAID) {
            PayLog success = payLogMapper.findSuccessByOrderId(order.getId());
            return buildPayResult(order, success, true);
        }

        String reason = payLog.getFailReason() == null
                ? ResultCode.PAY_FAILED.getMsg() : payLog.getFailReason();
        throw new BizException(ResultCode.PAY_FAILED.getCode(), reason);
    }

    private OrderActionResultVO buildPayResult(TicketOrder order,
                                               PayLog payLog,
                                               boolean replay) {
        OrderActionResultVO result = new OrderActionResultVO();
        result.setOrderNo(order.getOrderNo());
        result.setStatus(STATUS_PAID);
        result.setPaidAt(payLog != null && payLog.getPaidAt() != null
                ? payLog.getPaidAt() : order.getPaidAt());
        result.setPayNo(payLog == null ? null : payLog.getPayNo());
        result.setChannel(payLog == null ? null : payLog.getChannel());
        result.setIdempotentReplay(replay);
        return result;
    }

    private String resolveRequestId(String headerKey,
                                    PayOrderDTO dto,
                                    Long userId,
                                    String orderNo) {
        String raw = headerKey;
        if (raw == null || raw.isBlank()) {
            raw = dto == null ? null : dto.getRequestId();
        }
        if (raw == null || raw.isBlank()) {
            /*
             * 没传幂等键时用“用户 + 订单”生成固定键：
             * 同一用户对同一订单重复点击支付，天然只会生成一条流水。
             */
            return "AUTO:" + userId + ":" + orderNo;
        }
        raw = raw.trim();
        if (raw.length() > 64) {
            throw new BizException(400, "幂等键长度不能超过 64 个字符");
        }
        return raw;
    }

    private String resolveChannel(PayOrderDTO dto) {
        String channel = dto == null
                || dto.getChannel() == null
                || dto.getChannel().isBlank()
                ? "MOCK" : dto.getChannel().trim().toUpperCase();
        if (!"MOCK".equals(channel)) {
            throw new BizException(ResultCode.PAY_CHANNEL_NOT_SUPPORTED);
        }
        return channel;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderActionResultVO cancel(String orderNo) {
        Long userId = currentUserId();
        TicketOrder order = lockOwnedOrder(orderNo, userId);
        if (order.getStatus() != STATUS_PENDING_PAYMENT) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }

        int segmentCount =
                order.getToSegmentIndex() - order.getFromSegmentIndex() + 1;
        int affected = orderMapper.restoreStock(
                order.getTravelDate(),
                order.getTrainId(),
                order.getSeatType(),
                order.getFromSegmentIndex(),
                order.getToSegmentIndex(),
                order.getPassengerCount());
        if (affected != segmentCount) {
            /*
             * 库存行缺失或数据不一致时拒绝修改订单状态。
             * 因为还在事务中，已经回补的库存也会撤销，避免重复退款或余票异常。
             */
            throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
        }

        LocalDateTime now = LocalDateTime.now();
        int statusAffected = orderMapper.markCancelled(order.getId(), now);
        if (statusAffected != 1) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        cacheInvalidator.invalidateDateAfterCommit(order.getTravelDate());
        stockCache.incrementAfterCommit(
                order.getTravelDate(),
                order.getTrainId(),
                order.getSeatType(),
                order.getFromSegmentIndex(),
                order.getToSegmentIndex(),
                order.getPassengerCount());
        return actionResult(
                order.getOrderNo(), STATUS_CANCELLED, null, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RefundResultVO refund(String orderNo, RefundOrderDTO dto) {
        Long userId = currentUserId();
        if (new HashSet<>(dto.getOrderItemIds()).size()
                != dto.getOrderItemIds().size()) {
            throw new BizException(400, "同一张票不能重复选择");
        }

        // 先锁订单，再锁明细，所有退票请求都遵循同一加锁顺序。
        TicketOrder order = lockOwnedOrder(orderNo, userId);
        if (order.getStatus() != STATUS_PAID
                && order.getStatus() != STATUS_PARTIALLY_REFUNDED
                && order.getStatus() != STATUS_FULLY_REFUNDED) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }

        List<TicketOrderItem> items = orderMapper.findItemsByIdsForUpdate(
                order.getId(), dto.getOrderItemIds());
        if (items.size() != dto.getOrderItemIds().size()) {
            throw new BizException(ResultCode.ORDER_ITEM_NOT_FOUND);
        }

        BigDecimal refundAmount = BigDecimal.ZERO;
        for (TicketOrderItem item : items) {
            if (item.getStatus() == 2) {
                throw new BizException(ResultCode.ORDER_ITEM_ALREADY_REFUNDED);
            }
            if (item.getStatus() != 0 && item.getStatus() != 1) {
                throw new BizException(ResultCode.ORDER_STATUS_ERROR);
            }
            refundAmount = refundAmount.add(item.getPrice());
        }

        /*
         * 先检查具体明细，再拒绝“整单已经全部退票”。
         * 这样重复退同一张票能返回更准确的 ORDER_ITEM_ALREADY_REFUNDED。
         */
        if (order.getStatus() == STATUS_FULLY_REFUNDED) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }

        BigDecimal alreadyRefunded = order.getRefundedAmount() == null
                ? BigDecimal.ZERO : order.getRefundedAmount();
        if (alreadyRefunded.add(refundAmount)
                .compareTo(order.getTotalAmount()) > 0) {
            throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
        }

        int refundCount = items.size();
        int segmentCount =
                order.getToSegmentIndex() - order.getFromSegmentIndex() + 1;
        int stockAffected = orderMapper.restoreStock(
                order.getTravelDate(),
                order.getTrainId(),
                order.getSeatType(),
                order.getFromSegmentIndex(),
                order.getToSegmentIndex(),
                refundCount);
        if (stockAffected != segmentCount) {
            throw new BizException(ResultCode.ORDER_STOCK_RESTORE_ERROR);
        }

        LocalDateTime now = LocalDateTime.now();
        int itemAffected = orderMapper.markItemsRefunded(
                order.getId(), dto.getOrderItemIds(), now);
        if (itemAffected != refundCount) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }

        int totalRefunded = Math.toIntExact(
                orderMapper.countRefundedItems(order.getId()));
        int remainingTickets = Math.toIntExact(
                orderMapper.countUnrefundedItems(order.getId()));
        int orderStatus = remainingTickets == 0
                ? STATUS_FULLY_REFUNDED : STATUS_PARTIALLY_REFUNDED;

        int summaryAffected = orderMapper.updateRefundSummary(
                order.getId(),
                totalRefunded,
                refundAmount,
                now,
                orderStatus);
        if (summaryAffected != 1) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        cacheInvalidator.invalidateDateAfterCommit(order.getTravelDate());
        stockCache.incrementAfterCommit(
                order.getTravelDate(),
                order.getTrainId(),
                order.getSeatType(),
                order.getFromSegmentIndex(),
                order.getToSegmentIndex(),
                refundCount);

        RefundResultVO result = new RefundResultVO();
        result.setOrderNo(order.getOrderNo());
        result.setOrderStatus(orderStatus);
        result.setRefundedCount(totalRefunded);
        result.setRefundedAmount(alreadyRefunded.add(refundAmount));
        result.setRemainingTicketCount(remainingTickets);
        result.setRefundedAt(now);
        return result;
    }

    private TicketOrder lockOwnedOrder(String orderNo, Long userId) {
        TicketOrder order = orderMapper.findByOrderNoAndUserForUpdate(
                orderNo, userId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private OrderActionResultVO actionResult(String orderNo,
                                             Integer status,
                                             LocalDateTime paidAt,
                                             LocalDateTime cancelledAt) {
        OrderActionResultVO result = new OrderActionResultVO();
        result.setOrderNo(orderNo);
        result.setStatus(status);
        result.setPaidAt(paidAt);
        result.setCancelledAt(cancelledAt);
        return result;
    }

    /**
     * 价格表必须刚好覆盖区间中的每一段。
     * 少一段或多一段都说明车次配置不完整，不能猜价格继续下单。
     */
    private BigDecimal calculatePrice(List<TrainPriceVO> prices,
                                      int fromSegmentIndex,
                                      int expectedCount) {
        /*
         * 覆盖 4 段却只查到 3 段价格，说明车次价格配置不完整。
         * 不能自动补 0 元，也不能猜测价格，只能拒绝下单。
         */
        if (prices.size() != expectedCount) {
            throw new BizException(ResultCode.ORDER_PRICE_CONFIG_ERROR);
        }

        // BigDecimal 从 0 开始累加。金额计算不能用 double，避免浮点误差。
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < expectedCount; i++) {
            TrainPriceVO price = prices.get(i);

            /*
             * 段号必须连续。假设起始段是 2、应查 3 段，
             * 那么结果必须依次是 2、3、4，不能缺失或跳号。
             * signum() < 0 表示价格是负数，也要拒绝。
             */
            if (!price.getSegmentIndex().equals(fromSegmentIndex + i)
                    || price.getPrice() == null
                    || price.getPrice().signum() < 0) {
                throw new BizException(ResultCode.ORDER_PRICE_CONFIG_ERROR);
            }

            // BigDecimal 的加法是 add()，不能使用 + 运算符。
            total = total.add(price.getPrice());
        }
        return total;
    }

    private TicketOrder buildOrder(CreateOrderDTO dto,
                                   OrderRouteVO route,
                                   Long userId,
                                   int passengerCount,
                                   int fromSegmentIndex,
                                   int toSegmentIndex,
                                   BigDecimal pricePerPassenger,
                                   LocalDateTime now,
                                   String requestId) {
        TicketOrder order = new TicketOrder();

        // 业务订单号由“17 位时间 + 9 位随机数”组成，用户展示和排查日志都使用它。
        order.setOrderNo(generateOrderNo(now));

        /*
         * 同步下单幂等键。唯一索引 uk_order_request 是最终兜底：
         * 并发提交同一个 Key 时，只有一个事务能插入成功，其余回滚后重放已有订单。
         * 异步下单由 order_request.request_id 负责，这里保持 NULL。
         */
        order.setRequestId(requestId);

        // user_id 来自登录上下文，确认“谁下的单”。
        order.setUserId(userId);

        // trainId 用于内部关联；trainNo 是冗余快照，订单列表可直接显示 G1001。
        order.setTrainId(dto.getTrainId());
        order.setTrainNo(route.getTrainNo());

        // 保存行程信息和后端计算出的起止段号。
        order.setTravelDate(dto.getTravelDate());
        order.setFromStationId(dto.getFromStationId());
        order.setToStationId(dto.getToStationId());
        order.setFromSegmentIndex(fromSegmentIndex);
        order.setToSegmentIndex(toSegmentIndex);

        // 保存席别、人数和整张订单总额。
        order.setSeatType(dto.getSeatType());
        order.setPassengerCount(passengerCount);

        // 总金额 = 一位乘客的票价 × 乘车人数。
        order.setTotalAmount(pricePerPassenger.multiply(
                BigDecimal.valueOf(passengerCount)));

        // 刚创建的订单是“待支付”，库存已经在事务里预占。
        order.setStatus(STATUS_PENDING_PAYMENT);

        // 15 分钟内未支付就应由定时任务取消并回补库存。
        order.setExpireTime(now.plusMinutes(PAYMENT_TIMEOUT_MINUTES));
        return order;
    }

    private TicketOrderItem buildItem(Long orderId,
                                      Integer seatType,
                                      BigDecimal price,
                                      Passenger passenger) {
        TicketOrderItem item = new TicketOrderItem();

        // order_id 把明细挂到订单主表上。
        item.setOrderId(orderId);

        /*
         * passenger_id 保留原乘车人引用，同时保存姓名和证件号快照。
         * 即使乘车人以后改名或删除，历史订单仍然能显示购票时的信息。
         */
        item.setPassengerId(passenger.getId());
        item.setPassengerName(passenger.getName());
        item.setIdCardNo(passenger.getIdCardNo());

        // 这是“一张票”的席别和价格。
        item.setSeatType(seatType);
        item.setPrice(price);

        // 用户刚下单时还没有出具体车厢和座位，所以状态为待出票。
        item.setStatus(ITEM_PENDING_TICKET);
        return item;
    }

    private CreateOrderVO toCreateOrderVO(TicketOrder order) {
        // Entity 用于数据库，VO 用于返回前端，两者职责分开。
        CreateOrderVO vo = new CreateOrderVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }

    private String generateOrderNo(LocalDateTime now) {
        // 9 位随机数范围是 0～999,999,999，不足 9 位时前面补 0。
        int suffix = RANDOM.nextInt(1_000_000_000);
        return ORDER_TIME_FORMAT.format(now) + String.format("%09d", suffix);
    }

    private Long currentUserId() {
        /*
         * ThreadLocal 是“每个请求线程一份”的用户数据。
         * JwtInterceptor 验完 token 后 set，请求结束时 afterCompletion 再 clear。
         */
        LoginUser user = LoginUserContext.get();
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return user.getId();
    }
}
