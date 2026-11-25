package com.ticket.web;

import com.ticket.common.exception.BizException;
import com.ticket.common.exception.GlobalExceptionHandler;
import com.ticket.common.result.ResultCode;
import com.ticket.module.auth.AdminAuthController;
import com.ticket.module.auth.AdminAuthService;
import com.ticket.module.auth.AuthController;
import com.ticket.module.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录和注册 HTTP 契约测试。
 *
 * <p>业务码目前统一放在响应体 R.code 中，因此 HTTP 层重点验证：
 * JSON 解析、DTO 校验、业务异常转换和错误提示不可省略。
 */
class AuthControllerValidationTest {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AdminAuthService adminAuthService = mock(AdminAuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new AdminAuthController(adminAuthService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldRejectBlankLoginFields() throws Exception {
        mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("用户名不能为空")))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("密码不能为空")));
    }

    @Test
    void shouldRejectInvalidRegistrationFields() throws Exception {
        mockMvc.perform(post("/api/user/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "a!",
                                  "password": "123",
                                  "phone": "10086"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("用户名长度")));
    }

    @Test
    void shouldReturnBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求体格式不正确"));
    }

    @Test
    void shouldPreserveBusinessErrorCodeFromService() throws Exception {
        when(authService.register(any()))
                .thenThrow(new BizException(ResultCode.USERNAME_EXISTS));

        mockMvc.perform(post("/api/user/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "valid_user",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));
    }

    @Test
    void shouldRejectBlankAdminLoginFields() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"", "password":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("用户名不能为空")))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("密码不能为空")));
    }
}
