package com.ticket.module.auth.vo;

/**
 * 登录接口的出参：一个 token + 用户基本信息。
 *
 * <p>为什么登录要把用户信息一起返回？
 * 因为前端登录后要立刻显示「欢迎你，张三」，如果只给 token，
 * 前端还得再调一次 /me 接口才能拿到名字，白白多一次请求。
 *
 * <p>为什么 token 不放在 HTTP 响应头里，而是放在 body 里？
 * 因为响应头容易在跨域、网关转发时被过滤掉，放 body 最稳妥。
 * 前端拿到之后自己存进 localStorage，之后每次请求放进请求头。
 */
public class LoginVO {

    private String token;
    private UserVO user;

    public LoginVO() {
    }

    public LoginVO(String token, UserVO user) {
        this.token = token;
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public UserVO getUser() {
        return user;
    }

    public void setUser(UserVO user) {
        this.user = user;
    }
}
