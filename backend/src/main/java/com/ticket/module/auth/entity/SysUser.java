package com.ticket.module.auth.entity;

import java.time.LocalDateTime;

/**
 * 对应数据库 sys_user 表。
 *
 * <p>字段名和表字段一一对应，表字段是下划线（id_card_no），
 * Java 里换成驼峰（idCardNo）。这个转换由你在 SQL 里显式写别名完成，
 * 不要指望框架自动帮你转——写清楚才不容易出错。
 *
 * <p>注意：这个类**只在 Service 和 Mapper 之间流转**，
 * 绝不直接返回给前端。因为它身上带着 password 字段，一旦返回出去，
 * 前端按 F12 就能看到密码哈希。对外要用 UserVO。
 */
public class SysUser {

    private Long id;
    private String username;
    private String password;
    private String phone;
    private String nickname;
    private String avatar;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
