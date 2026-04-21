package com.science.ai.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：创建 RedissonClient，供聊天记忆写锁与压缩锁使用。
 * 输入：spring.data.redis 中的连接参数。
 * 输出：单例 RedissonClient。
 * 边界条件：当前仅使用单节点模式，后续可按部署拓扑切换哨兵/集群配置。
 */
@Configuration
public class RedisLockConfig {

    /**
     * 关键步骤：
     * 1) 基于 Spring Redis 配置拼接 redis:// 地址；
     * 2) 使用 Redisson 单机模式创建客户端；
     * 3) 注册为 Spring Bean 供业务注入。
     * 异常处理：连接参数非法时由 Redisson 初始化阶段抛错，沿用应用启动失败策略。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:2}") int database) {

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setPassword(password == null || password.trim().isEmpty() ? null : password.trim());

        return Redisson.create(config);
    }
}

