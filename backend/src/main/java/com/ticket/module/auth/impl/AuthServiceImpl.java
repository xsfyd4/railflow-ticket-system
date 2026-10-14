package com.ticket.module.auth.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.auth.AuthService;
import com.ticket.module.auth.dto.LoginDTO;
import com.ticket.module.auth.dto.RegisterDTO;
import com.ticket.module.auth.entity.SysUser;
import com.ticket.module.auth.mapper.SysUserMapper;
import com.ticket.module.auth.vo.LoginVO;
import com.ticket.module.auth.vo.UserVO;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import com.ticket.security.JwtUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 注册功能的具体实现。
 *
 * <p>Service 层是整个后端的重心：业务规则、事务边界都在这里。
 * Controller 只负责收参数，Mapper 只负责执行 SQL，中间的判断都归它管。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * @Transactional 的作用：这个方法里的所有数据库操作，要么全成功，要么全回滚。
     *
     * <p>注册这里其实只有一条 INSERT，用不用事务看起来没差别。
     * 但养成习惯有好处——到了下单那一块（扣库存 + 写订单 + 写明细），
     * 少写一个注解就可能出现「库存扣了但订单没生成」这种脏数据。
     *
     * <p>rollbackFor = Exception.class 不能省。Spring 默认只对运行时异常回滚，
     * 万一以后抛出一个受检异常，不加这个参数事务就会悄悄提交，坑很深。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterDTO dto) {

        // 1. 业务规则：用户名不能被占用
        if (sysUserMapper.countByUsername(dto.getUsername()) > 0) {
            throw new BizException(ResultCode.USERNAME_EXISTS);
        }

        // 2. 组装实体
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        // ★ 关键：存进数据库的必须是密文，绝不能是 dto.getPassword()
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        // ★ 空字符串要转成 null。因为 sys_user.phone 建了唯一索引，
        //   如果两个用户都不填手机号，两条 '' 会撞唯一索引导致注册失败；
        //   而 MySQL 的唯一索引允许多个 NULL 并存。
        user.setPhone(StringUtils.hasText(dto.getPhone()) ? dto.getPhone() : null);
        user.setStatus(1);

        // 3. 落库
        try {
            return sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 上面查过一次，为什么还要再兜一层？
            // 因为「查询」和「插入」之间有极小的时间窗口：
            // 两个人同时注册同一个用户名，都查到 0 条，然后都去插入。
            // 这时数据库的唯一索引会挡住第二个，抛出 DuplicateKeyException。
            // 数据库的约束才是最后一道防线，代码里的检查只是为了让提示更友好。
            throw new BizException(ResultCode.USERNAME_EXISTS);
        }
    }

    /**
     * 登录。
     *
     * <p>这个方法没有加 @Transactional，因为它只读不写。
     * 事务是有成本的（开启连接、加锁），用在只读方法上纯属浪费。
     *
     * <p>注意密码比对的写法：不是「把库里的密文解密再和输入比对」，
     * 而是「把输入的密码用同样的算法加密，再比对结果」。
     * BCrypt 是单向的，压根不支持解密。它每次加密结果还不一样（自动加盐），
     * 所以必须用 matches() 让它内部处理，不能自己比字符串。
     */
    @Override
    public LoginVO login(LoginDTO dto) {

        // 1. 按用户名查用户
        SysUser user = sysUserMapper.findByUsername(dto.getUsername());

        // 2. 用户不存在 和 密码错误，故意用同一句提示。
        //    如果分开提示「用户不存在」和「密码错误」，等于告诉攻击者
        //    「zhangsan 这个账号是真实存在的，你接着猜密码吧」——
        //    这就把用户名枚举攻击的门打开了。
        //    注意 user == null 时也必须走到这里，不能提前 return，否则耗时差异也会泄露信息。
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException(ResultCode.LOGIN_FAILED);
        }

        // 3. 账号被禁用不允许登录
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        // 4. 签发 token。角色写死 USER，管理端登录时会传 ADMIN
        String token = jwtUtil.generate(user.getId(), user.getUsername(), JwtUtil.ROLE_USER);

        // 5. 组装返回。注意用的是 UserVO.from()，password 不会出现在响应里
        return new LoginVO(token, UserVO.from(user));
    }

    /**
     * 查当前登录人。
     *
     * <p>这个方法没有加 @Transactional，因为它只读不写。
     *
     * <p>只有三步：从 ThreadLocal 里拿 id、按 id 查库、转成 VO。
     * 看起来简单，但有个前提——**必须先经过 JwtInterceptor**。
     * 如果某个路径忘了被拦截器保护，LoginUserContext 里就是空的，
     * 这里会抛 401 挡住，所以漏配拦截器不会变成安全漏洞，只会变成"这个接口用不了"。
     */
    @Override
    public UserVO getCurrentUser() {

        // 1. 当前登录人。没有就说明这个请求没经过拦截器（或者拦截器放行了却没 set）
        LoginUser loginUser = LoginUserContext.get();
        if (loginUser == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }

        // 2. 按 id 现查数据库，拿最新的昵称、手机号
        SysUser user = sysUserMapper.findById(loginUser.getId());

        // 3. token 有效但用户已经被删了（比如管理员删号），同样视为未登录
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }

        return UserVO.from(user);
    }
}
