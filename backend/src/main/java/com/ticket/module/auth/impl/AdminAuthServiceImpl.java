package com.ticket.module.auth.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.auth.AdminAuthService;
import com.ticket.module.auth.dto.LoginDTO;
import com.ticket.module.auth.entity.SysAdmin;
import com.ticket.module.auth.entity.SysUser;
import com.ticket.module.auth.mapper.SysAdminMapper;
import com.ticket.module.auth.vo.AdminLoginVO;
import com.ticket.module.auth.vo.AdminVO;
import com.ticket.module.auth.vo.LoginVO;
import com.ticket.module.auth.vo.UserVO;
import com.ticket.security.JwtUtil;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 管理端登录的实现。
 *
 * <p>对照 AuthServiceImpl 里的 login() 和 getCurrentUser() 写，几乎一模一样。
 * 只有三处不同：
 * <ol>
 *   <li>查的是 sys_admin 表（用 SysAdminMapper）</li>
 *   <li>签 token 时角色传 {@link JwtUtil#ROLE_ADMIN}，不是 ROLE_USER</li>
 *   <li>返回的是 AdminVO，不是 UserVO</li>
 * </ol>
 *
 * <p>需要 import 的东西应该和 AuthServiceImpl 差不多，看着补：
 * BizException、ResultCode、SysAdmin、LoginUser、LoginUserContext。
 */
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    private final SysAdminMapper sysAdminMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AdminAuthServiceImpl(SysAdminMapper sysAdminMapper,
                                PasswordEncoder passwordEncoder,
                                JwtUtil jwtUtil) {
        this.sysAdminMapper = sysAdminMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 管理员登录。
     *
     * <p>提示：步骤和 AuthServiceImpl.login 完全一致——
     * 查用户 → 比对密码 → 查状态 → 签 token → 组装返回。
     * 其中两个地方要格外注意：
     * <ul>
     *   <li>"查不到"和"密码错"必须用同一个错误码（LOGIN_FAILED），别分开提示</li>
     *   <li>签 token 时角色一定要传 ROLE_ADMIN，
     *       传错了管理端的拦截器会把你当成普通用户挡在门外</li>
     * </ul>
     */
    @Override
    public AdminLoginVO login(LoginDTO dto) {
        // 1. 按用户名查用户
        SysAdmin user = sysAdminMapper.findByUsername(dto.getUsername());
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
        String token = jwtUtil.generate(user.getId(), user.getUsername(), JwtUtil.ROLE_ADMIN);

        // 5. 组装返回。注意用的是 UserVO.from()，password 不会出现在响应里
        return new AdminLoginVO(token, AdminVO.from(user));

    }

    /**
     * 查当前登录的管理员。
     *
     * <p>提示：步骤和 AuthServiceImpl.getCurrentUser 一致——
     * 从 LoginUserContext 拿 id → 查库 → 转 AdminVO。
     *
     * <p>小问题留给你想：现在 SysAdminMapper 只有 findByUsername，
     * 那这里怎么按 id 查？你有两个选择，选一个并想清楚为什么。
     */
    @Override
    public AdminVO getCurrentAdmin() {
        // 1. 当前登录人。没有就说明这个请求没经过拦截器（或者拦截器放行了却没 set）
        LoginUser loginUser = LoginUserContext.get();
        if (loginUser == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }

        // 2. 按 id 现查数据库，拿最新的昵称、手机号
        SysAdmin user = sysAdminMapper.findById(loginUser.getId());

        // 3. token 有效但用户已经被删了（比如管理员删号），同样视为未登录
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }

        return AdminVO.from(user);
    }
}
