package com.ticket.config;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.JedisPoolConfig;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 启动时不主动发现 Sentinel，第一次真正访问 Redis 时才建连接池。
 *
 * <p>这样 Redis 先宕机、应用后启动时，Spring 仍能启动并进入 MySQL
 * 降级路径。多个并发请求同时触发初始化时，只允许一个线程尝试，
 * 其他线程快速失败，避免在同一个 Redis 超时上排长队。
 */
public class LazyJedisConnectionFactory extends JedisConnectionFactory {

    private final ReentrantLock startupLock = new ReentrantLock();

    public LazyJedisConnectionFactory(
            RedisSentinelConfiguration configuration,
            JedisPoolConfig poolConfig) {
        super(configuration, poolConfig);
        setAutoStartup(false);
        setEarlyStartup(false);
    }

    public LazyJedisConnectionFactory(
            RedisStandaloneConfiguration configuration) {
        super(configuration);
        setAutoStartup(false);
        setEarlyStartup(false);
    }

    @Override
    public RedisConnection getConnection() {
        ensureStarted();
        return super.getConnection();
    }

    @Override
    public void start() {
        try {
            super.start();
        } catch (RuntimeException error) {
            resetFailedStartState(error);
            throw error;
        }
    }

    private void ensureStarted() {
        if (isRunning()) {
            return;
        }
        if (!startupLock.tryLock()) {
            throw new IllegalStateException(
                    "Redis 连接正在初始化，请稍后重试");
        }
        try {
            if (isRunning()) {
                return;
            }
            try {
                start();
            } catch (RuntimeException error) {
                throw error;
            }
        } finally {
            startupLock.unlock();
        }
    }

    /**
     * Spring Data Redis 4.1.1 在 Sentinel 初始化抛异常时不会把
     * STARTING 改回 STOPPED。这里按固定依赖版本复位，使下一次探测可重试。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resetFailedStartState(RuntimeException original) {
        try {
            Field stateField = JedisConnectionFactory.class
                    .getDeclaredField("state");
            stateField.setAccessible(true);
            AtomicReference state = (AtomicReference) stateField.get(this);
            Class<?> stateType = Class.forName(
                    "org.springframework.data.redis.connection.jedis."
                            + "JedisConnectionFactory$State");
            state.set(Enum.valueOf((Class<? extends Enum>) stateType,
                    "STOPPED"));
        } catch (ReflectiveOperationException resetError) {
            original.addSuppressed(resetError);
        }
    }
}
