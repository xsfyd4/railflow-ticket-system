package com.ticket.module.auth.mapper;

import com.ticket.module.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * sys_user 表的数据访问层（MyBatis 版）。
 *
 * <p>和之前 JdbcTemplate 版的区别，一眼就能看出来：**这里只有方法声明，没有一行实现。**
 *
 * <p>那实现去哪了？在 {@code resources/mapper/SysUserMapper.xml} 里。
 * MyBatis 在启动时会把 XML 和这个接口"挂上钩"：
 * XML 里的 {@code namespace} 写的是这个接口的全限定名，
 * 每个 {@code <select id="xxx">} 的 id 对应接口里的同名方法。
 * 所以你调用 {@code findByUsername("zhangsan")} 时，
 * MyBatis 会拿这个方法名去 XML 里找 id 相同的语句执行。
 *
 * <p><b>方法名和 XML 里的 id 必须一模一样</b>，大小写都不能差。
 * 写错了不会编译报错，而是在启动时报 "Invalid bound statement (not found)"——
 * 这个错误几乎人人都踩过，记住它的长相。
 *
 * <p>@Mapper 是 MyBatis 的注解，作用是告诉 MyBatis
 * "这是个 Mapper 接口，启动时给它生成一个实现类"。
 * 有了它，Spring 容器里就会多一个 SysUserMapper 类型的 Bean，
 * Service 直接注入就能用——你从头到尾没有写过任何实现类。
 *
 * <p>@Param 用来给参数起名，这样 XML 里就能写 {@code #{username}}。
 * 不写也行（单参数时 MyBatis 会自动用参数名），但显式写出来更清楚，
 * 而且多参数时必须写。
 */
@Mapper
public interface SysUserMapper {

    /** 统计某个用户名有多少条记录。对应 XML 里的 countByUsername */
    int countByUsername(@Param("username") String username);

    /** 按用户名查用户，查不到返回 null。对应 XML 里的 findByUsername */
    SysUser findByUsername(@Param("username") String username);

    /** 按主键查用户，查不到返回 null。对应 XML 里的 findById */
    SysUser findById(@Param("id") Long id);

    /**
     * 插入一条用户记录，返回影响的行数（也就是 1）。
     *
     * <p><b>注意返回值和以前不一样了。</b>以前 JdbcTemplate 版返回的是自增主键，
     * 现在返回影响行数——MyBatis 的规矩是"插入操作返回影响行数"。
     *
     * <p>那自增主键去哪了？**被 MyBatis 塞回参数对象里了**。
     * XML 的 insert 上写了 {@code useGeneratedKeys="true" keyProperty="id"}，
     * 意思是"把数据库生成的主键，写回到参数对象的 id 属性上"。
     * 所以调用完之后，直接 {@code user.getId()} 就能拿到新主键。
     *
     * <p>这是个必须记住的差异——用 JdbcTemplate 时你靠 KeyHolder 接主键，
     * 用 MyBatis 时主键自己就回到对象里了。
     */
    int insert(SysUser user);
}
