package com.ticket.module.auth.vo;

/**
 * 管理端登录的返回：token + 管理员信息。
 *
 * <p>结构上和 LoginVO 一模一样。为什么不直接复用 LoginVO？
 * 因为 LoginVO 里的字段类型写死是 UserVO，而管理员的字段（realName、role）
 * 和用户不一样。硬凑的话要么改 UserVO，要么塞一堆用不上的 null 字段。
 * **结构相似不等于可以复用**——这是设计时要做的判断。
 */
public class AdminLoginVO {

    private String token;
    private AdminVO admin;

    public AdminLoginVO() {
    }

    public AdminLoginVO(String token, AdminVO admin) {
        this.token = token;
        this.admin = admin;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public AdminVO getAdmin() {
        return admin;
    }

    public void setAdmin(AdminVO admin) {
        this.admin = admin;
    }
}
