package com.ticket.security;

import com.ticket.common.result.R;
import com.ticket.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理端拦截器。
 *
 * <p>做法：<b>把 JwtInterceptor 整个复制过来，然后只加一处角色判断。</b>
 * 不要凭空重写，两份代码保持同样的结构，以后改动时才好对比。
 *
 * <p>唯一的关键区别：验完 token 之后，还要检查 role 是不是 ADMIN。
 *
 * <p>为什么必须查？因为普通用户登录拿到的 token 是**合法且没过期**的，
 * 签名完全正确。如果不查角色，任何登录过的用户都能调管理端接口 ——
 * 那就等于没有权限管理。
 *
 * <p>这一条就是整个项目的权限隔离线，写的时候想清楚它在防谁。
 *
 * <p>角色不对时返回 <b>403</b>，不是 401：
 * <ul>
 *   <li>401 = "我不知道你是谁"（token 没有 / 无效 / 过期）</li>
 *   <li>403 = "我知道你是谁，但你没资格进这个门"</li>
 * </ul>
 * 错误码用 ResultCode.FORBIDDEN（403，"没有权限访问该资源"），
 * 它已经在阶段 1 的 ResultCode 里定义好了。
 *
 * <p>需要的东西：字段（JwtUtil、ObjectMapper）、构造器、
 * preHandle、afterCompletion、以及一个写 403 的私有方法
 * （照着 JwtInterceptor 的 writeUnauthorized 改，把状态码和 ResultCode 换掉）。
 */
@Component
public class AdminJwtInterceptor implements HandlerInterceptor {

    // TODO 全部照抄 JwtInterceptor，只在 preHandle 里加角色判断
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public  AdminJwtInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
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
        // 角色不是 ADMIN → 403（不是 401）
        if (!JwtUtil.ROLE_ADMIN.equals(role)) {
            writeForbidden(response);
            return false;
        }
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
    private void writeForbidden(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(R.fail(ResultCode.FORBIDDEN)));
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
