package com.ticket.module.auth.entity;

import java.time.LocalDateTime;

/**
 * 对应数据库 sys_admin 表。
 *
 * <p>和 SysUser 长得像，但有三处不一样，写的时候对照着看：
 * <ul>
 *   <li>没有 nickname / phone / avatar，多了 realName（真实姓名）</li>
 *   <li>多了 role 字段（SUPER / ADMIN）——这是管理端权限的来源</li>
 *   <li>多了 lastLoginAt（最后登录时间）</li>
 * </ul>
 *
 * <p>表里的列名是下划线，Java 字段用驼峰：real_name → realName，
 * last_login_at → lastLoginAt。这个对应关系要自己记住，没有东西会自动帮你转。
 *
 * <p>表里还有 created_at 和 updated_at 两列，但现在没有任何地方要用它们，
 * 所以先不写进这个类。**用不到的字段不要加**——加了就得在 SQL 里查、
 * 在 RowMapper 里赋值，全是白干的活。等真要显示"管理员创建时间"时再加。
 *
 * <p>最后：这个类带着 password，不要直接返回给前端（用 AdminVO）。
 */
public class SysAdmin {

    private Long id;
    private String username;
    private String password;
    private String realName;
    private String role;
    private Integer status;
    private LocalDateTime lastLoginAt;

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
