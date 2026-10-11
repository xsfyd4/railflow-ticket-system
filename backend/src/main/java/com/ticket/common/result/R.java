package com.ticket.common.result;

/**
 * 统一返回体：所有接口都返回这个结构。
 *
 * <p>形如：code + msg + data。成功时 code=200，失败时 code 是具体的业务错误码。
 *
 * <p>为什么一定要统一？因为前端只需要写一次「怎么判断请求成功、失败时怎么提示」，
 * 后面几十个接口都能复用。如果不统一，前端每个页面都要写一套解析逻辑。
 *
 * <p>注意：这里的 code 是业务码，不是 HTTP 状态码。
 * HTTP 状态码大部分情况都返回 200，具体成功失败看 body 里的 code。
 */
public class R<T> {

    private int code;
    private String msg;
    private T data;

    public R() {
    }

    public R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /** 成功，没有数据（比如删除成功） */
    public static <T> R<T> ok() {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    /** 成功，带数据 */
    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /** 成功，自定义提示语（比如「注册成功，请登录」） */
    public static <T> R<T> ok(String msg, T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), msg, data);
    }

    /** 失败，用预定义的错误码 */
    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /** 失败，自定义错误码和提示语 */
    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
