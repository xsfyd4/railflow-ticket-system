package com.ticket.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册接口的入参。
 *
 * <p>为什么不用 SysUser 直接接收参数？
 * 因为「前端能传什么」和「数据库存什么」是两回事：
 * <ul>
 *   <li>SysUser 里有 id、status、createdAt，这些绝不能让前端传进来，
 *       否则别人可以自己指定 id 或把自己设成管理员</li>
 *   <li>注册还需要「确认密码」这种字段，数据库里并不存</li>
 * </ul>
 *
 * <p>校验注解写在字段上，Controller 加一个 @Valid 就会自动生效。
 * 校验不通过时 Spring 抛 MethodArgumentNotValidException，
 * 由 GlobalExceptionHandler 统一转成 400 和友好提示——
 * 这样 Service 里就不用写一堆 if 判空了。
 */
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度要在 4 到 20 位之间")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度要在 6 到 20 位之间")
    private String password;

    @Size(max = 50, message = "昵称最长 50 个字")
    private String nickname;

    /** 手机号可以不填；填了就必须是合法的 11 位号码 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

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

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
