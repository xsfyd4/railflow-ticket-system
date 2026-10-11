package com.ticket.common.exception;

import com.ticket.common.result.R;
import com.ticket.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>作用：Controller / Service 抛出的任何异常，都在这里统一转成 R 结构返回给前端。
 * 没有它的话，一旦出错，前端收到的是 Spring 默认的一大坨错误 JSON 或 HTML 错误页，
 * 前端根本没法统一处理。
 *
 * <p>@RestControllerAdvice 会拦住所有 Controller 抛出的异常。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：属于「预期内」的错误，按警告记录，不打堆栈 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        log.warn("业务异常：code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 兜底：任何没被上面接住的异常都到这里。
     *
     * <p>这里一定要打完整堆栈（log.error 带上异常对象），
     * 因为能走到这里的，基本都是我们没预料到的 bug，必须留下线索。
     * 但返回给前端的只有一句通用提示，不能把堆栈暴露出去。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail(ResultCode.ERROR);
    }
}
