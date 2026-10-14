package com.ticket.module.auth.impl;

import com.ticket.module.auth.AdminAuthService;
import com.ticket.module.auth.dto.LoginDTO;
import com.ticket.module.auth.entity.SysAdmin;
import com.ticket.module.auth.mapper.SysAdminMapper;
import com.ticket.module.auth.vo.AdminLoginVO;
import com.ticket.module.auth.vo.AdminVO;
import com.ticket.security.JwtUtil;
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
        // TODO
        SysAdmin sysAdmin;
        throw new UnsupportedOperationException("TODO: 管理端登录");

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
        // TODO
        throw new UnsupportedOperationException("TODO: 查当前管理员");
    }
}
