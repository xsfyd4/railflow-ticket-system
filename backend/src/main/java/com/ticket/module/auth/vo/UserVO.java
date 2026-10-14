package com.ticket.module.auth.vo;

import com.ticket.module.auth.entity.SysUser;

/**
 * 返回给前端的用户信息。
 *
 * <p><b>请注意这个类里没有 password 字段。</b>
 * 这不是疏忽，是设计：SysUser 实体身上带着密码哈希，
 * 只要它有机会被当成返回值，就总有一天会被不小心返回出去。
 * 用 UserVO 把「能给人看的字段」挑出来，风险就从「靠人记得」变成了「靠类型保证」。
 *
 * <p>顺带处理另一件事：手机号脱敏。数据库里存的是完整的 13800000000，
 * 但界面上显示 138****0000 更合适。脱敏放在 from() 里做，
 * 所有返回用户信息的地方就自动都是脱敏的，不用每个接口各写一遍。
 */
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String phone;

    /**
     * 把实体转成 VO。写成静态工厂方法，是为了让「怎么转」这件事只有一处定义。
     * 以后加了新字段，只改这里，所有调用点自动生效。
     */
    public static UserVO from(SysUser user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(maskPhone(user.getPhone()));
        return vo;
    }

    /** 13800000000 -> 138****0000；手机号为空时原样返回 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
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
