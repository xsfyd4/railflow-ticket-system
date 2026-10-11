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
    ERROR(500, "服务器内部错误，请稍后重试"),

    USERNAME_EXISTS(1001, "用户名已存在"),
    LOGIN_FAILED(1002, "用户名或密码错误"),
    ACCOUNT_DISABLED(1003, "账号已被禁用"),

    TICKET_NOT_ENOUGH(2001, "该区间余票不足"),
    ORDER_STATUS_ERROR(2002, "订单当前状态不允许此操作"),
    ORDER_EXPIRED(2003, "订单已超时，请重新下单"),
    TRAIN_NOT_FOUND(2004, "未找到符合条件的车次");

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
