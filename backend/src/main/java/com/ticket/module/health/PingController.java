package com.ticket.module.health;

import com.ticket.common.result.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口：阶段 1 的验收标准就是它能返回 JSON。
 *
 * <p>它的作用不是「有用」，而是证明整条链路是通的：
 * 浏览器 → Tomcat → Spring MVC → Controller → R 包装 → JSON 响应。
 * 以后前端联调、部署上线，第一个要访问的也是它。
 */
@RestController
@RequestMapping("/api/public")
public class PingController {

    /** GET http://localhost:8080/api/public/ping */
    @GetMapping("/ping")
    public R<Map<String, Object>> ping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "train-ticket-backend");
        data.put("status", "ok");
        data.put("time", LocalDateTime.now().toString());
        return R.ok(data);
    }
}
