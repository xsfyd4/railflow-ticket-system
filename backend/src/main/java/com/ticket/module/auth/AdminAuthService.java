package com.ticket.module.auth;

import com.ticket.module.auth.dto.LoginDTO;
import com.ticket.module.auth.vo.AdminLoginVO;
import com.ticket.module.auth.vo.AdminVO;

/**
 * 管理端登录的业务接口。
 *
 * <p>为什么不把这些方法加到 AuthService 里？
 * 因为那一堆是"用户端账号"的业务（操作 sys_user 表），
 * 这里是"管理端账号"的业务（操作 sys_admin 表）。
 * 虽然现在长得像，但以后会分叉——用户端要做注册、改密码、绑定手机，
 * 管理端要做角色管理、启用停用。混在一个接口里，两边都会被对方的改动拖累。
 *
 * <p>入参直接复用了 LoginDTO（用户名 + 密码），因为两边要的东西完全一样，
 * 没必要为了"各自独立"再造一个一模一样的类。
 * **复用的判断标准是"内容是否真的相同"，不是"属于谁"。**
 */
public interface AdminAuthService {

    /**
     * 管理员登录。
     *
     * @param dto 用户名和密码
     * @return token + 管理员信息
     * @throws com.ticket.common.exception.BizException
     *         用户名或密码错误时抛 1002；账号被禁用时抛 1003
     */
    AdminLoginVO login(LoginDTO dto);

    /**
     * 查当前登录的管理员。
     *
     * @return 管理员信息（不含密码）
     * @throws com.ticket.common.exception.BizException 未登录或账号已不存在时抛 401
     */
    AdminVO getCurrentAdmin();
}
