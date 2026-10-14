package com.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密器配置。
 *
 * <p>为什么要把 PasswordEncoder 注册成 Bean，而不是每次用到就 new 一个？
 * 因为加密策略是全局统一的——今天用 BCrypt，以后想换 Argon2，
 * 只改这一个地方，所有用到它的代码都不用动。
 *
 * <p>BCrypt 有三个特点，正好解决密码存储的三个问题：
 * <ol>
 *   <li>单向不可逆：加密后无法还原成明文，数据库泄露也拿不到密码</li>
 *   <li>自动加盐：同一个密码每次加密结果都不同，
 *       攻击者没法用「彩虹表」（预先算好的密码与密文对照表）批量破解</li>
 *   <li>故意算得慢：默认迭代 10 轮，暴力破解的成本被拉高很多</li>
 * </ol>
 *
 * <p>用法只有两个：注册时 encode()，登录时 matches()。
 * 永远不要试图「解密」——BCrypt 压根不支持解密。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
