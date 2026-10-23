package com.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类。
 *
 * <p>@SpringBootApplication 这一个注解干了三件事：
 * <ol>
 *   <li>声明这是一个 Spring Boot 应用</li>
 *   <li>开启自动配置（自动配好 Tomcat、JSON 转换器等）</li>
 *   <li>把本类所在的包（com.ticket）及所有子包纳入扫描，
 *       所以 Controller 必须放在 com.ticket 下面，否则扫不到</li>
 * </ol>
 *
 * <p>启动方式：运行本类的 main 方法即可。
 */
@SpringBootApplication
@EnableScheduling
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
