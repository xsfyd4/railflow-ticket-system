package com.ticket.config;

import com.ticket.security.AdminJwtInterceptor;
import com.ticket.security.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 相关配置。
 *
 * <p>开发阶段前端跑在 5173 / 5174 端口，后端跑在 8080，浏览器的同源策略会拦住请求，
 * 所以这里先放开跨域。
 *
 * <p>注意 allowedOriginPatterns("*") 只适合本地开发，上线前必须改成具体域名，
 * 否则任何网站都能调你的接口。部署时再回来改这里。
 *
 * <p>以后要加登录拦截器，也是在 config 包下新写一个拦截器类，
 * 然后在本类的 addInterceptors 方法里注册进来。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AdminJwtInterceptor adminJwtInterceptor;

    public WebMvcConfig(JwtInterceptor jwtInterceptor,
                        AdminJwtInterceptor adminJwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
        this.adminJwtInterceptor = adminJwtInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用户端：除注册和登录外，全部要求 token
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/user/**")
                .excludePathPatterns("/api/user/auth/register",
                        "/api/user/auth/login");

        // 管理端：除登录外，全部要求 token 且角色必须是 ADMIN
        registry.addInterceptor(adminJwtInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/auth/login");
    }
}
