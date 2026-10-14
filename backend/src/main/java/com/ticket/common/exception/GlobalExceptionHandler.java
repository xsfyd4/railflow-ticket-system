package com.ticket.common.exception;

import com.ticket.common.result.R;
import com.ticket.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

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
     * 参数校验失败（Controller 上加了 @Valid，DTO 字段校验没过）。
     *
     * <p>这是阶段 2 新加的。没有它的话，参数校验失败会掉进下面的兜底方法，
     * 返回「服务器内部错误 500」，但真正的原因是「用户填的内容不合法」，
     * 属于客户端的错，应该是 400 才准确。
     *
     * <p>Spring 会把所有校验失败的字段塞进 BindingResult，
     * 这里把它们的中文提示拼起来返回，前端直接弹出来给用户看。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(org.springframework.validation.FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        if (msg.isBlank()) {
            msg = ResultCode.BAD_REQUEST.getMsg();
        }
        log.warn("参数校验失败：{}", msg);
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 请求的地址或静态资源不存在，返回 404。
     *
     * <p>为什么会需要这个方法？浏览器每次打开页面都会自动去要 /favicon.ico（标签页小图标），
     * 我们没有这个文件，Spring 就抛 NoResourceFoundException。
     * 如果让下面的兜底方法接住，它会被当成「系统异常」打一整屏错误堆栈——
     * 明明只是访问了一个不存在的地址，却搞得像服务出故障了。
     *
     * <p>这引出一个重要原则：异常分两类，要区别对待。
     * 「客户端错误」（404、参数不对）是正常现象，日志降级；
     * 「服务端错误」（空指针、数据库连不上）才是要报警的。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.debug("资源不存在：{}", e.getMessage());
        return R.fail(ResultCode.NOT_FOUND);
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
