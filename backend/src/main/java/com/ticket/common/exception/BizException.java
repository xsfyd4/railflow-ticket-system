package com.ticket.common.exception;

import com.ticket.common.result.ResultCode;

/**
 * 业务异常。
 *
 * <p>什么时候用它？当「代码没写错，但业务上不允许」时：
 * 余票不足、用户名已存在、订单已超时不能再支付……都属于这一类。
 *
 * <p>它继承 RuntimeException，所以不用在方法上写 throws，代码更干净。
 * 抛出后会被 GlobalExceptionHandler 接住，转成给前端的 R.fail，不会刷一屏错误堆栈。
 *
 * <p>对比一下：NullPointerException 属于「代码有 bug」，必须要记录；
 * 而「余票不足」是正常业务结果，不需要报警。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
