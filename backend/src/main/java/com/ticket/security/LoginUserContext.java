package com.ticket.security;

public class LoginUserContext {

    /** 每个线程自己的那份数据 */
    private static final ThreadLocal<LoginUser> CURRENT_USER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        CURRENT_USER.set(user);
    }

    public static LoginUser get() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }

    /** 工具类，不该被 new，用私有构造器把这条路堵死 */
    private LoginUserContext() {
    }
}