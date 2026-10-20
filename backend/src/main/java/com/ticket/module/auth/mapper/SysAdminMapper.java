package com.ticket.module.auth.mapper;

import com.ticket.module.auth.entity.SysAdmin;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * sys_admin 表的数据访问层（MyBatis 版）。
 *
 * <p>对照 {@link SysUserMapper} 写。管理员现在需要两个能力：
 * 按用户名查（登录校验用）、按 id 查（查当前登录人用）。
 *
 * <p>规矩不变：**只执行 SQL，不做业务判断**。
 * 「密码对不对」是业务规则，写在 Service 里。
 */
@Mapper
public interface SysAdminMapper {

    /** 按用户名查管理员，查不到返回 null。对应 XML 里的 findByUsername */
    SysAdmin findByUsername(@Param("username") String username);

    /**
     * 按主键查管理员，查不到返回 null。对应 XML 里的 findById。
     *
     * <p>为什么「查当前登录人」要按 id 查，而不是按用户名？
     * 因为**用户名是可以改的**。token 里存的是签发那一刻的用户名，
     * 如果管理员后来改了用户名，用旧名字去查就会查不到，用户会莫名其妙被踢下线。
     * 而主键 id 一旦生成就不会变，是稳定的身份标识。
     *
     * <p>这也是一个通用原则：**拿来做身份定位的字段，要选不会变的那个。**
     */
    SysAdmin findById(@Param("id") Long id);
}
