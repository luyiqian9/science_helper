package com.science.ai.config.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 职责：承载聊天归档配置，控制 MySQL 归档开关与默认 userId。
 * 输入：application.yml 中 app.chat.archive 前缀配置项。
 * 输出：为仓储层提供可读取的归档参数。
 * 边界条件：当未透传真实用户身份时，使用 defaultUserId 兜底，确保归档逻辑可持续运行。
 */
@Component
@ConfigurationProperties(prefix = "app.chat.archive")
public class ChatArchiveProperties {

    /**
     * 是否启用 MySQL 归档。默认开启，便于在当前阶段直接落库。
     */
    private boolean mysqlEnabled = true;

    /**
     * 默认用户标识。当前系统尚未引入用户体系时使用该值。
     */
    private String defaultUserId = "anonymous";

    public boolean isMysqlEnabled() {
        return mysqlEnabled;
    }

    public void setMysqlEnabled(boolean mysqlEnabled) {
        this.mysqlEnabled = mysqlEnabled;
    }

    public String getDefaultUserId() {
        return defaultUserId;
    }

    public void setDefaultUserId(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }
}

