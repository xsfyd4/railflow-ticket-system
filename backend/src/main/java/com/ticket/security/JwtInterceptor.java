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

        // TODO 1：如果是 OPTIONS 预检请求，直接 return true 放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // TODO 2：取出 Authorization 请求头
        //         为空、或者不是以 "Bearer " 开头 → writeUnauthorized(response); return false;
        String header = request.getHeader(HEADER);

        // 判断一：根本没带
// 判断二：带了，但格式不对（不是以 "Bearer " 开头）
        if (header == null || !header.startsWith(PREFIX)) {
            writeUnauthorized(response);
            return false;
        }

        String token = header.substring(PREFIX.length());   // 砍掉前 7 个字符
        // TODO 3：截掉 "Bearer " 前缀，拿到真正的 token

        // TODO 4：解析 token
        //         try { Claims claims = jwtUtil.parse(token); ... }
        //         catch (JwtException e) { writeUnauthorized(response); return false; }
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (JwtException e) {
            writeUnauthorized(response);
            return false;
        }
        // TODO 5：从 claims 里取 sub（用户 id）、name（用户名）、role（角色）
        //         注意 claims.getSubject() 返回的是 String，id 要转成 Long
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("name", String.class);
        String role = claims.get("role", String.class);

        LoginUserContext.set(new LoginUser(userId, username, role));
        // TODO 6：LoginUserContext.set(new LoginUser(id, username, role));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        LoginUserContext.clear();
    }

    /**
     * 往响应里写 401。这块是纯 API 操作，没有理解难度，直接给你。
     * 注意：拦截器里 return false 不会自动产生响应体，必须自己写。
     */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(R.fail(ResultCode.UNAUTHORIZED)));
    }
}