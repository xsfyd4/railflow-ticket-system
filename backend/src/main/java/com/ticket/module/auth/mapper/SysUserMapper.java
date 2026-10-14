package com.ticket.module.auth.mapper;

import com.ticket.module.auth.entity.SysUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * sys_user 表的数据访问层。
 *
 * <p>这一层的规矩很简单：<b>只执行 SQL，不做任何业务判断</b>。
 * 「用户名不能重复」是业务规则，写在 Service 里；
 * 这里只提供「数一下这个用户名有几条」这个能力。
 *
 * <p>为什么方法里全部用 `?` 占位符，而不是把参数拼进 SQL 字符串？
 * 因为拼接字符串会导致 SQL 注入：用户在输入框里填
 * `' OR '1'='1` 这种内容，拼出来的 SQL 就变味了。
 * 用 `?` 时，参数是作为「数据」发给 MySQL 的，不会被当成 SQL 语法解析。
 * 这是最基础也最重要的安全习惯，从第一行 SQL 就要养成。
 */
@Repository
public class SysUserMapper {

    /**
     * 查用户时统一取这几列，所有查询共用。
     *
     * <p>为什么不写 SELECT *？三个理由：
     * <ol>
     *   <li>表以后加字段（比如加个 signature 签名），SELECT * 会把没用的数据也捞回来</li>
     *   <li>列名写死之后，数据库改列名时这里会立刻报错，
     *       而 SELECT * 会悄悄跑到运行时才崩</li>
     *   <li>写成常量，两个查询共用，以后加字段只改一处，不会漏</li>
     * </ol>
     */
    private static final String USER_COLUMNS =
            "id, username, password, phone, nickname, avatar, status";

    /**
     * 把结果集的一行转成 SysUser 对象。
     *
     * <p>抽成静态常量而不是写在方法里，是因为 findByUsername 和 findById
     * 需要完全一样的转换逻辑。写两遍的后果是：以后 SysUser 加一个字段，
     * 你改了其中一个忘了另一个，于是某个接口突然少了字段，还很难查。
     */
    private static final RowMapper<SysUser> ROW_MAPPER = (rs, rowNum) -> {
        SysUser user = new SysUser();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPassword(rs.getString("password"));
        user.setPhone(rs.getString("phone"));
        user.setNickname(rs.getString("nickname"));
        user.setAvatar(rs.getString("avatar"));
        user.setStatus(rs.getInt("status"));
        return user;
    };

    private final JdbcTemplate jdbcTemplate;

    public SysUserMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 统计某个用户名有多少条记录。返回 0 表示没被占用。 */
    public int countByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?",
                Integer.class, username);
        return count == null ? 0 : count;
    }

    /**
     * 按用户名查用户。查不到返回 null。
     */
    public SysUser findByUsername(String username) {
        List<SysUser> list = jdbcTemplate.query(
                "SELECT " + USER_COLUMNS + " FROM sys_user WHERE username = ?",
                ROW_MAPPER, username);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按主键查用户。查不到返回 null。
     *
     * <p>这个方法给「查当前登录人」用：拦截器从 token 里解析出 userId，
     * 再拿它来这里查完整的用户信息。
     */
    public SysUser findById(Long id) {
        List<SysUser> list = jdbcTemplate.query(
                "SELECT " + USER_COLUMNS + " FROM sys_user WHERE id = ?",
                ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入一条用户记录，返回数据库生成的自增主键。
     *
     * <p>为什么要用 KeyHolder 这么绕的写法？
     * 因为主键是 MySQL 自增生成的，插入前我们在 Java 里并不知道它是几。
     * KeyHolder 就是用来「接住」数据库回传的这个主键值的。
     * 后面下单、写订单明细都要靠它拿到刚插入那行的 id。
     */
    public Long insert(SysUser user) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password, phone, nickname, status) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPassword());
            ps.setString(3, user.getPhone());
            ps.setString(4, user.getNickname());
            ps.setInt(5, user.getStatus());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }
}
