package com.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录接口的入参。
 *
 * <p>比注册的 DTO 简单得多，因为登录只需要两样东西。
 * 注意这里**没有** @Size 之类的长度校验——登录时再去校验密码长度没意义，
 * 密码对不对应该由「比对结果」说了算，长度不合法自然就比对不上。
 */
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
