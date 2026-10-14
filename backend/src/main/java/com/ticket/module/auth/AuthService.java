package com.ticket.module.auth;

import com.ticket.module.auth.dto.LoginDTO;
import com.ticket.module.auth.dto.RegisterDTO;
import com.ticket.module.auth.vo.LoginVO;
import com.ticket.module.auth.vo.UserVO;

/**
 * 登录注册的业务接口。
 *
 * <p>为什么先写接口（interface）再写实现（impl）？
 * 对小项目来说，直接写一个类也能跑，但接口能带来两个实际好处：
 * <ol>
 *   <li>Controller 依赖的是接口，不关心具体怎么实现。
 *       以后要把「查数据库」换成「查缓存」，换的是实现类，Controller 一行都不用改</li>
 *   <li>读代码时先看这个接口，一眼就知道这个模块对外提供哪些能力，
 *       不用翻几百行实现</li>
 * </ol>
 *
 * <p>每个方法的注释要写清楚「做什么、什么情况下会抛什么异常」，
 * 因为调用方（Controller）只看到这个文件。
 */
public interface AuthService {

    /**
     * 注册新用户。
     *
     * @param dto 注册参数
     * @return 新用户的主键 id
     * @throws com.ticket.common.exception.BizException 用户名已被占用时抛出（错误码 1001）
     */
    Long register(RegisterDTO dto);

    /**
     * 登录。
     *
     * @param dto 用户名和密码
     * @return token + 用户基本信息
     * @throws com.ticket.common.exception.BizException
     *         用户名或密码错误时抛 1002；账号被禁用时抛 1003
     */
    LoginVO login(LoginDTO dto);

    /**
     * 查当前登录人的信息。
     *
     * <p>「当前是谁」不是靠参数传进来的，而是拦截器验完 token 之后
     * 存进 LoginUserContext 的，这里去取就行。
     *
     * <p>注意：虽然 token 里已经有用户名了，但这里还是要按 id 重新查一次数据库。
     * 因为 token 是签发那一刻的快照，用户后来改了昵称、换了手机号，
     * token 里还是旧的。要展示给用户看的实时数据，必须现查。
     *
     * @return 当前登录人的信息（不含密码）
     * @throws com.ticket.common.exception.BizException
     *         没有登录信息、或用户记录已被删除时抛 401
     */
    UserVO getCurrentUser();
}
