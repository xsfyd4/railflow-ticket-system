package com.ticket.common.result;

/**
 * 全局错误码。
 *
 * <p>为什么要把错误码写成枚举，而不是到处写魔法数字？
 * 因为前端要按 code 判断，后端要按 code 返回，两边一旦对不上就是线上事故。
 * 集中在一个文件里，改的时候只改一处，也能一眼看完整个系统有哪些错误情况。
 *
 * <p>号段约定：
 * <ul>
 *   <li>200 / 400 / 401 / 403 / 500：通用</li>
 *   <li>1xxx：账号相关</li>
 *   <li>2xxx：车票与订单相关</li>
 * </ul>
 */
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有权限访问该资源"),
    NOT_FOUND(404, "请求的资源不存在"),
    ERROR(500, "服务器内部错误，请稍后重试"),

    USERNAME_EXISTS(1001, "用户名已存在"),
    LOGIN_FAILED(1002, "用户名或密码错误"),
    ACCOUNT_DISABLED(1003, "账号已被禁用"),

    TICKET_NOT_ENOUGH(2001, "该区间余票不足"),
    ORDER_STATUS_ERROR(2002, "订单当前状态不允许此操作"),
    ORDER_EXPIRED(2003, "订单已超时，请重新下单"),
    TRAIN_NOT_FOUND(2004, "未找到符合条件的车次"),
    ORDER_PRICE_CONFIG_ERROR(2005, "该车次暂不可售，请联系管理员"),
    ORDER_NOT_FOUND(2006, "订单不存在"),
    ORDER_STOCK_RESTORE_ERROR(2007, "订单库存回补失败，请联系管理员"),
    ORDER_ITEM_NOT_FOUND(2008, "订单明细不存在或不属于该订单"),
    ORDER_ITEM_ALREADY_REFUNDED(2009, "该张票已经退票"),

    PAY_REQUEST_CONFLICT(2010, "该幂等键已被其他订单使用"),
    PAY_PROCESSING(2011, "支付处理中，请稍后查询订单状态"),
    PAY_FAILED(2012, "支付失败"),
    PAY_CHANNEL_NOT_SUPPORTED(2013, "暂不支持该支付渠道"),
    PAY_LOG_STATE_ERROR(2014, "支付流水状态异常，请联系管理员"),
    ASYNC_REQUEST_NOT_FOUND(2015, "异步下单请求不存在"),
    ASYNC_REQUEST_CONFLICT(2016, "该幂等键已用于另一次下单"),
    ASYNC_REDIS_UNAVAILABLE(2017, "下单排队服务暂不可用，请稍后重试"),
    ASYNC_MQ_UNAVAILABLE(2018, "下单队列暂不可用，请稍后重试"),
    ORDER_IDEMPOTENCY_CONFLICT(2019, "该下单幂等键已被其他订单使用"),
    ORDER_IDEMPOTENCY_PROCESSING(2020, "订单请求正在处理中，请稍后查询"),

    STATION_CODE_EXISTS(3001, "车站三字码已存在"),
    STATION_NOT_FOUND(3002, "车站不存在"),
    STATION_IN_USE(3003, "该车站已被车次使用，请改为禁用"),

    TRAIN_NO_EXISTS(3004, "车次号已存在"),
    TRAIN_IN_USE(3005, "该车次已有订单，不能删除"),

    PASSENGER_NOT_FOUND(4001, "乘车人不存在"),
    PASSENGER_ID_CARD_EXISTS(4002, "该乘车人已存在"),
    PASSENGER_LIMIT_EXCEEDED(4003, "常用乘车人数量已达上限");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
