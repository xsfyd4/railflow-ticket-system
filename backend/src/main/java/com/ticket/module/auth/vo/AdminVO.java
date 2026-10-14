package com.ticket.module.auth.vo;

import com.ticket.module.auth.entity.SysAdmin;

/**
 * 返回给前端的管理员信息。
 *
 * <p>和 UserVO 一样，这里**没有 password 字段**，是故意的。
 * SysAdmin 实体身上带着密码哈希，只要它有被当成返回值的机会，早晚会出事。
 *
 * <p>和 UserVO 不同的是：不需要手机号脱敏，但多了 role——
 * 前端可能要根据角色决定显示哪些菜单。
 */
public class AdminVO {

    private Long id;
    private String username;
    private String realName;
    private String role;

    /**
     * 把实体转成 VO。
     *
     * <p>对照 UserVO.from() 写。这里没有要脱敏的字段，所以比 UserVO 还简单。
     * 记得处理 admin 为 null 的情况（返回 null）。
     */
    public static AdminVO from(SysAdmin admin) {
        // TODO
        throw new UnsupportedOperationException("TODO: SysAdmin 转 AdminVO");
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
