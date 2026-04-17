package com.science.ai.service.archive;

import com.science.ai.repository.mysql.ChatArchiveRepository;

import java.util.List;

/**
 * 职责：提供聊天归档门面服务，对外统一暴露 fail-safe 归档入口。
 * 输入：
 * 1) conversationKey(type:chatId) 与消息归档载荷；
 * 2) 会话索引参数 bizType/chatId。
 * 输出：无返回值；门面内部统一处理开关、默认 userId、异常降级。
 * 边界条件：
 * 1) 当归档开关关闭时直接跳过，不影响 Redis 主链路；
 * 2) 当 MySQL 写入异常时仅记录日志，不向上抛出以避免中断聊天。
 */
public interface ChatArchiveFacadeService {

    /**
     * 关键步骤：
     * 1) 校验归档开关；
     * 2) 统一注入默认 userId；
     * 3) fail-safe 调用消息归档仓储。
     * 异常处理：内部吞掉归档异常并记录 error 日志。
     */
    void archiveMessagesFailSafe(String conversationKey, List<ChatArchiveRepository.ArchiveMessagePayload> payloads);

    /**
     * 关键步骤：
     * 1) 校验归档开关；
     * 2) 统一注入默认 userId；
     * 3) fail-safe 调用会话索引 upsert。
     * 异常处理：内部吞掉归档异常并记录 error 日志。
     */
    void saveSessionIndexFailSafe(String bizType, String chatId);
}

