package com.ticket.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Spring Boot、MySQL、JWT 拦截器组成的登录鉴权链路。
 *
 * <p>该测试刻意不直接调用 Service，而是从 HTTP 入口验证：
 * 注册 → 登录 → 携带 Bearer token → 访问用户端/管理端接口。
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "ticket.redis.warmup.enabled=false",
        "ticket.redis.reconcile.enabled=false"
})
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

    private static final String PASSWORD = "ApiTest123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String username;
    private String adminUsername;

    @BeforeEach
    void setUp() {
        String suffix = String.format(
                "%010d",
                Math.floorMod(System.nanoTime(), 10_000_000_000L));
        username = "apit_u_" + suffix;
        adminUsername = "apit_a_" + suffix;
        cleanup();

        jdbcTemplate.update("""
                INSERT INTO sys_user
                    (username, password, nickname, status)
                VALUES (?, ?, ?, 1)
                """, username, passwordEncoder.encode(PASSWORD), "接口测试用户");
        jdbcTemplate.update("""
                INSERT INTO sys_admin
                    (username, password, real_name, role, status)
                VALUES (?, ?, ?, 'SUPER', 1)
                """, adminUsername, passwordEncoder.encode(PASSWORD), "接口测试管理员");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldLoginAndAccessUserAndAdminApisWithCorrectRoles()
            throws Exception {
        String userToken = loginAndGetToken(
                "/api/user/auth/login", username);

        mockMvc.perform(get("/api/user/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username));

        mockMvc.perform(get("/api/admin/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        String adminToken = loginAndGetToken(
                "/api/admin/auth/login", adminUsername);
        mockMvc.perform(get("/api/admin/auth/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(adminUsername));
    }

    @Test
    void shouldRejectMissingInvalidAndTamperedTokens() throws Exception {
        mockMvc.perform(get("/api/user/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/user/auth/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String token = loginAndGetToken(
                "/api/user/auth/login", username);
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);
        mockMvc.perform(get("/api/user/auth/me")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void shouldRegisterThroughApiAndRejectDuplicateUsername()
            throws Exception {
        String newUsername = username + "_r";
        MvcResult created = mockMvc.perform(
                        post("/api/user/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "username": "%s",
                                          "password": "%s",
                                          "nickname": "新用户"
                                        }
                                        """.formatted(newUsername, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        Number userId = JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.data.userId");
        assertFalse(userId.longValue() <= 0);

        mockMvc.perform(post("/api/user/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s"
                                }
                                """.formatted(newUsername, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    private String loginAndGetToken(String path, String loginUsername)
            throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s"
                                }
                                """.formatted(loginUsername, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.data.token");
    }

    private void cleanup() {
        jdbcTemplate.update("""
                DELETE FROM sys_user
                WHERE username LIKE 'apit_u_%'
                """);
        jdbcTemplate.update("""
                DELETE FROM sys_admin
                WHERE username LIKE 'apit_a_%'
                """);
    }
}
