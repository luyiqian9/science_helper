package com.science.ai.config.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 职责：提供异步执行器，承载聊天记忆压缩任务。
 * 输入：无外部输入，基于固定线程池参数创建执行器。
 * 输出：供 @Async("chatMemoryCompressExecutor") 使用的线程池 Bean。
 * 边界条件：当任务突增时启用队列缓冲，避免挤占主请求线程。
 */
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    /**
     * 关键步骤：
     * 1) 配置核心线程与最大线程，控制并发压缩规模；
     * 2) 配置队列容量，吸收短时突发流量；
     * 3) 配置线程名前缀，便于日志排障。
     * 异常处理：线程池拒绝策略使用默认策略，保留 Spring 标准行为。
     */
    @Bean("chatMemoryCompressExecutor")
    public Executor chatMemoryCompressExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 配置核心线程数 平时常驻
        executor.setCorePoolSize(2);
        // 配置最大线程数 最忙时开的数量
        executor.setMaxPoolSize(4);
        // 配置队列容量 超过最大线程数时 排队等候数量
        executor.setQueueCapacity(200);
        // 配置线程名前缀
        executor.setThreadNamePrefix("chat-memory-compress-");
        executor.initialize();
        return executor;
    }
}

