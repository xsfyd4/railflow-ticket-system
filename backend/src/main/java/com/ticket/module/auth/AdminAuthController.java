package com.ticket.module.auth;

import com.ticket.common.result.R;
import com.ticket.module.auth.dto.LoginDTO;
import com.ticket.module.auth.entity.SysAdmin;
import com.ticket.module.auth.mapper.SysAdminMapper;
import com.ticket.module.auth.vo.AdminLoginVO;
import com.ticket.module.auth.vo.AdminVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端登录接口。
 *
 * <p>路径前缀是 /api/admin，不是 /api/user —— 这个前缀决定了它会被哪个拦截器管。
 * 用户端的拦截器只管 /api/user/**，所以这两个接口目前是"不设防"的，
 * 等你写完 AdminJwtInterceptor 并在 WebMvcConfig 里注册之后才受保护。
 *
 * <p>对照 AuthController 写，结构完全一样。
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    /**
     * 管理员登录。
     *
     * <pre>
     * POST /api/admin/auth/login
     * { "username": "admin", "password": "123456" }
     *
     * { "code": 200, "msg": "操作成功",
     *   "data": { "token": "eyJhbGciOi...",
     *             "admin": { "id": 1, "username": "admin", "realName": "超级管理员", "role": "SUPER" } } }
     * </pre>
     */
    @PostMapping("/login")
    public R<AdminLoginVO> login(@RequestBody @Valid LoginDTO dto) {
        return R.ok(adminAuthService.login(dto));
    }

    /**
     * 查当前登录的管理员。
     *
     * <pre>
     * GET /api/admin/auth/me
     * Authorization: Bearer eyJhbGciOi...
     * </pre>
     *
     * <p>和用户端的 /me 一样，方法不需要任何参数。
     */
    @GetMapping("/me")
    public R<AdminVO> me() {
       return(R.ok(adminAuthService.getCurrentAdmin()));
    }
}
