package com.science.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfiguration implements WebMvcConfigurer {

    // 1. 定义一个真正的线程池（专门给 Web 流式输出用的）
    @Bean(name = "mvcAsyncExecutor")
    public ThreadPoolTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);       // 核心线程数（平时留10个处理打字机流式输出）
        executor.setMaxPoolSize(50);        // 最忙时开到50个
        executor.setQueueCapacity(100);     // 排队队列
        executor.setThreadNamePrefix("Web-Async-"); // 给线程起个名字，以后看日志一目了然
        executor.initialize();
        return executor;
    }

    // 2. 将这个线程池配置给 Spring MVC 的异步支持
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 设置自定义的线程池
        configurer.setTaskExecutor(mvcAsyncExecutor());
        // 可选：设置流式输出的超时时间（比如 60 秒），防止大模型卡死导致 HTTP 连接永远不断开
        configurer.setDefaultTimeout(60000L);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
