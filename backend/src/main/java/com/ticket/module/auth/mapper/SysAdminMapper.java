package com.ticket.module.auth.mapper;

import com.ticket.module.auth.entity.SysAdmin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * sys_admin 表的数据访问层。
 *
 * <p>对照 SysUserMapper 写。管理员现在只需要一个能力：
 * 按用户名查出来，给登录校验用。
 *
 * <p>规矩不变：<b>只执行 SQL，不做业务判断</b>。
 * 「密码对不对」是业务规则，写在 Service 里。
 */
@Repository
public class SysAdminMapper {

    /**
     * 查询时取哪几列？
     *
     * <p>提示：只取 SysAdmin 这个类里声明过的字段，而且要和它一一对应。
     * created_at / updated_at 用不到，不要取——取了也没地方放。
     *
     * <p>格式参考 SysUserMapper 里的 USER_COLUMNS：一串用逗号分开的列名。
     */
    private static final String ADMIN_COLUMNS =
            // TODO 填列名
            "TODO";

    /**
     * 结果集的一行 → SysAdmin 对象。
     *
     * <p>对照 SysUserMapper.ROW_MAPPER 写。列名和字段名的对应关系：
     * real_name → realName，last_login_at → lastLoginAt。
     *
     * <p>提示：last_login_at 在数据库里可能是 NULL（从没登录过），
     * 直接 getTimestamp() 再 toLocalDateTime() 会空指针，要判断一下。
     */
    private static final RowMapper<SysAdmin> ROW_MAPPER = (rs, rowNum) -> {
        // TODO
        throw new UnsupportedOperationException("TODO: 结果集转 SysAdmin");
    };

    private final JdbcTemplate jdbcTemplate;

    public SysAdminMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按用户名查管理员，查不到返回 null。
     *
     * <p>对照 SysUserMapper.findByUsername 写，SQL 里记得用 ADMIN_COLUMNS。
     */
    public SysAdmin findByUsername(String username) {
        // TODO
        throw new UnsupportedOperationException("TODO: 按用户名查管理员");
    }
}
