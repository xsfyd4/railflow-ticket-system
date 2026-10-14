package com.ticket.security;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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
}
