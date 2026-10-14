package com.ticket.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 工具类：负责签发 token 和解析 token。
 *
 * <p><b>先想清楚 JWT 到底是什么。</b>它长这样：
 *
 * <pre>
 * eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwicm9sZSI6IlVTRVIifQ.abc123signature
 * └──── 头部 ────┘ └────────── 载荷 payload ──────────┘ └── 签名 ──┘
 * </pre>
 *
 * <p>头两段只是 Base64 编码，<b>任何人复制出来粘到 jwt.io 就能看到内容</b>。
 * 它保证的不是「保密」，而是「没被改过」——第三段签名是用密钥算出来的，
 * 改一个字符签名就对不上，服务端会直接拒绝。
 *
 * <p>所以两条铁律：
 * <ol>
 *   <li>payload 里只放 id、角色、用户名这种不敏感的标识，
 *       绝不放密码、身份证、手机号</li>
 *   <li>密钥必须保密。密钥泄露 = 别人可以自己签发任意用户的 token，
 *       等于整个系统的门锁被复制了</li>
 * </ol>
 *
 * <p>为什么用 JWT 而不是「服务端存一个 session」？
 * 因为 JWT 是无状态的：服务端不存任何东西，只靠签名验证，
 * 所以天然支持多台服务器横向扩展。代价是签发出去就撤不回来
 * （所以退出登录只能靠前端丢弃 token）。
 */
@Component
public class JwtUtil {

    /** 角色：普通用户 */
    public static final String ROLE_USER = "USER";
    /** 角色：管理员 */
    public static final String ROLE_ADMIN = "ADMIN";

    private final SecretKey key;
    private final long expireSeconds;
    private final String issuer;

    /**
     * 构造器注入配置值。@Value 会把 application.yml 里的配置读出来传进来，
     * 所以密钥、有效期都写在配置文件里，代码不用改。
     */
    public JwtUtil(@Value("${ticket.jwt.secret}") String secret,
                   @Value("${ticket.jwt.expire-seconds}") long expireSeconds,
                   @Value("${ticket.jwt.issuer}") String issuer) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
        this.issuer = issuer;
    }

    /**
     * 签发 token。
     *
     * @param userId   用户主键
     * @param username 用户名
     * @param role     角色，USER 或 ADMIN
     */
    public String generate(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                // sub（subject）放用户 id，这是 JWT 规范里「这是谁」的标准字段
                .subject(String.valueOf(userId))
                .issuer(issuer)
                // 自定义字段，解析时用 claims.get("role") 取
                .claim("name", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                // exp：过期时间。超时后解析会抛 ExpiredJwtException
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 token。
     *
     * <p>这个方法会同时做三件事：验签名（有没有被篡改）、验签发者、验过期时间。
     * 任何一项不过关都会抛 JwtException，调用方直接当成「无效 token」处理即可。
     *
     * <p>过期时会抛出子类 ExpiredJwtException，如果想给前端返回
     * 「登录已过期，请重新登录」而不是笼统的「未登录」，可以单独 catch 它。
     *
     * @throws JwtException token 无效、被篡改或已过期
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
