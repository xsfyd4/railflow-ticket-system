package com.ticket.security;

import tools.jackson.databind.ObjectMapper;
import com.ticket.common.result.R;
import com.ticket.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public JwtInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 浏览器的 CORS 预检请求不携带业务 token。
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            writeUnauthorized(response);
            return false;
        }

        String token = header.substring(PREFIX.length());
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (JwtException e) {
            writeUnauthorized(response);
            return false;
        }
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("name", String.class);
        String role = claims.get("role", String.class);

        LoginUserContext.set(new LoginUser(userId, username, role));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        LoginUserContext.clear();
    }

    /** 拦截器返回 false 时仍需主动写入统一的 401 响应体。 */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(R.fail(ResultCode.UNAUTHORIZED)));
    }
}
