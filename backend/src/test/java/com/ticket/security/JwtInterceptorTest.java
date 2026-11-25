package com.ticket.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户端和管理端 JWT 拦截器的 HTTP 行为测试。
 *
 * <p>这里不启动数据库，直接验证 token 解析、角色判断、统一 JSON 响应
 * 和 ThreadLocal 清理，保证安全边界不依赖具体业务接口。
 */
class JwtInterceptorTest {

    private static final String SECRET =
            "test-jwt-secret-key-at-least-32-bytes-long";
    private static final String ISSUER = "train-ticket-test";

    private JwtUtil jwtUtil;
    private JwtInterceptor userInterceptor;
    private AdminJwtInterceptor adminInterceptor;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 60, ISSUER);
        ObjectMapper objectMapper = new ObjectMapper();
        userInterceptor = new JwtInterceptor(jwtUtil, objectMapper);
        adminInterceptor = new AdminJwtInterceptor(jwtUtil, objectMapper);
    }

    @AfterEach
    void tearDown() {
        LoginUserContext.clear();
    }

    @Test
    void shouldRejectMissingAndMalformedAuthorizationHeader()
            throws Exception {
        MockHttpServletResponse missing = invoke(userInterceptor, null);
        assertEquals(401, missing.getStatus());
        assertTrue(missing.getContentAsString(StandardCharsets.UTF_8)
                .contains("\"code\":401"));

        MockHttpServletResponse malformed =
                invoke(userInterceptor, "not-a-bearer-token");
        assertEquals(401, malformed.getStatus());
    }

    @Test
    void shouldRejectTamperedAndExpiredToken() throws Exception {
        String valid = jwtUtil.generate(
                7L, "user7", JwtUtil.ROLE_USER);
        int signatureStart = valid.lastIndexOf('.') + 1;
        char replacement = valid.charAt(signatureStart) == 'a' ? 'b' : 'a';
        String tampered = valid.substring(0, signatureStart)
                + replacement
                + valid.substring(signatureStart + 1);
        MockHttpServletResponse invalidResponse =
                invoke(userInterceptor, "Bearer " + tampered);
        assertEquals(401, invalidResponse.getStatus());

        JwtUtil expiredIssuer =
                new JwtUtil(SECRET, -1, ISSUER);
        String expired = expiredIssuer.generate(
                7L, "user7", JwtUtil.ROLE_USER);
        MockHttpServletResponse expiredResponse =
                invoke(userInterceptor, "Bearer " + expired);
        assertEquals(401, expiredResponse.getStatus());
    }

    @Test
    void shouldAcceptUserTokenAndSetLoginContext() throws Exception {
        String token = jwtUtil.generate(
                11L, "normalUser", JwtUtil.ROLE_USER);
        MockHttpServletResponse response =
                invoke(userInterceptor, "Bearer " + token);

        assertEquals(200, response.getStatus());
        LoginUser loginUser = LoginUserContext.get();
        assertEquals(11L, loginUser.getId());
        assertEquals("normalUser", loginUser.getUsername());
        assertEquals(JwtUtil.ROLE_USER, loginUser.getRole());
    }

    @Test
    void shouldRejectUserTokenOnAdminEndpoint() throws Exception {
        String userToken = jwtUtil.generate(
                11L, "normalUser", JwtUtil.ROLE_USER);
        MockHttpServletResponse response =
                invoke(adminInterceptor, "Bearer " + userToken);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
                .contains("\"code\":403"));
        assertNull(LoginUserContext.get());
    }

    @Test
    void shouldAcceptAdminTokenOnAdminEndpoint() throws Exception {
        String adminToken = jwtUtil.generate(
                21L, "adminUser", JwtUtil.ROLE_ADMIN);
        MockHttpServletResponse response =
                invoke(adminInterceptor, "Bearer " + adminToken);

        assertEquals(200, response.getStatus());
        LoginUser admin = LoginUserContext.get();
        assertEquals(21L, admin.getId());
        assertEquals(JwtUtil.ROLE_ADMIN, admin.getRole());
    }

    @Test
    void shouldAllowCorsPreflightWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "OPTIONS", "/api/user/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(userInterceptor.preHandle(
                request, response, new Object()));
        assertNull(LoginUserContext.get());
    }

    private MockHttpServletResponse invoke(
            org.springframework.web.servlet.HandlerInterceptor interceptor,
            String authorization) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/user/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        interceptor.preHandle(request, response, new Object());
        return response;
    }
}
