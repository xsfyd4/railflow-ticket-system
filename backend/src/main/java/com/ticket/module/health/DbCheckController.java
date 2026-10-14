package com.ticket.module.health;

import com.ticket.common.result.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库连通性探针：阶段 2 第 1 步的验收标准。
 *
 * <p>它做三件事，用来证明「后端 → MySQL」这条路通了：
 * <ol>
 *   <li>问 MySQL 当前连的是哪个库</li>
 *   <li>数一下 station 表有几行</li>
 *   <li>把车站名字列出来（顺便验证中文不乱码）</li>
 * </ol>
 *
 * <p>期望结果：库名 train_ticket、车站 5 个，列表里能看到北京南和上海虹桥。
 *
 * <p>关于 JdbcTemplate：Spring 自带的数据库工具类。你写 SQL，
 * 它负责拿连接、执行、关连接、把结果转成 Java 对象这些杂活。
 * 它是线程安全的，可以放心注入到任何地方。
 */
@RestController
@RequestMapping("/api/public")
public class DbCheckController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入：Spring 看到这个构造器，会自动把容器里的 JdbcTemplate 传进来。
     * 你从没在哪儿 new 过它，这就是「依赖注入」——只声明需要什么，由 Spring 提供。
     */
    public DbCheckController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** GET http://localhost:8080/api/public/db-check */
    @GetMapping("/db-check")
    public R<Map<String, Object>> dbCheck() {
        // 1. 当前连的是哪个库？
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

        // 2. station 表里有几行？
        Integer stationCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM station", Integer.class);

        // 3. 把站名查出来，顺便验证中文不乱码（JDBC 的经典坑）
        List<String> stationNames = jdbcTemplate.queryForList(
                "SELECT station_name FROM station ORDER BY id", String.class);

        // LinkedHashMap 按 put 的顺序输出，返回的 JSON 字段顺序才稳定
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", database);
        data.put("stationCount", stationCount);
        data.put("stations", stationNames);
        return R.ok(data);
    }
}
