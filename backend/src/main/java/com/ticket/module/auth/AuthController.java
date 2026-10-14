package com.ticket.module.auth;

import com.ticket.common.result.R;
import com.ticket.module.auth.dto.LoginDTO;
import com.ticket.module.auth.dto.RegisterDTO;
import com.ticket.module.auth.vo.LoginVO;
import com.ticket.module.auth.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户端登录注册接口。
 *
 * <p>Controller 只做三件事：收参数、调 Service、包装返回。
 * 里面看不到一句业务判断，也看不到 try-catch。
 */
@RestController
@RequestMapping("/api/user/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册新用户。
     *
     * <pre>
     * POST /api/user/auth/register
     * { "username": "zhangsan", "password": "123456", "nickname": "张三", "phone": "13800000000" }
     *
     * { "code": 200, "msg": "注册成功，请登录", "data": { "userId": 1 } }
     * </pre>
     *
     * <p>@RequestBody：把请求体里的 JSON 转成 RegisterDTO 对象。
     *
     * <p>@Valid：触发 RegisterDTO 字段上的校验注解。
     * 校验不通过会抛 MethodArgumentNotValidException，
     * 由 GlobalExceptionHandler 转成 { "code": 400, "msg": "用户名不能为空" }。
     * 注意：**没有 @Valid，那些 @NotBlank 注解就是摆设，一点用都没有**——
     * 这是新手最常犯的错误之一。
     *
     * <p>返回里带上 userId，是为了方便你等下测试时看到效果。
     */
    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody @Valid RegisterDTO dto) {
        Long userId = authService.register(dto);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        return R.ok("注册成功，请登录", data);
    }

    /**
     * 登录。
     *
     * <pre>
     * POST /api/user/auth/login
     * { "username": "zhangsan", "password": "123456" }
     *
     * { "code": 200, "msg": "操作成功",
     *   "data": { "token": "eyJhbGciOi...", "user": { "id": 1, "username": "zhangsan", ... } } }
     * </pre>
     *
     * <p>前端拿到 token 后要做两件事：
     * <ol>
     *   <li>存起来（localStorage）</li>
     *   <li>之后每个请求都放进请求头：{@code Authorization: Bearer <token>}</li>
     * </ol>
     *
     * <p>下一步要写的拦截器，就是从请求头里把这个 token 取出来验的。
     */
    @PostMapping("/login")
    public R<LoginVO> login(@RequestBody @Valid LoginDTO dto) {
        return R.ok(authService.login(dto));
    }

    /**
     * 查当前登录人。
     *
     * <pre>
     * GET /api/user/auth/me
     * Authorization: Bearer eyJhbGciOi...
     *
     * { "code": 200, "msg": "操作成功",
     *   "data": { "id": 1, "username": "zhangsan", "nickname": "张三", "phone": "138****0000" } }
     * </pre>
     *
     * <p>注意这个方法**一个参数都没有**，也不需要 @RequestBody。
     * 「当前是谁」由拦截器验完 token 后存进 LoginUserContext，
     * Service 自己去取。Controller 完全不用关心这件事。
     *
     * <p>不带 token 访问会返回 401 —— 那是 JwtInterceptor 挡下来的，
     * 根本走不到这个方法里。
     *
     * <p>调试接口时如果这个接口返回 401，先确认两件事：
     * 一是请求头写成了 {@code Authorization: Bearer <token>}（Bearer 后面有一个空格），
     * 二是 token 还没过期（默认 7 天）。
     */
    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(authService.getCurrentUser());
    }
}
