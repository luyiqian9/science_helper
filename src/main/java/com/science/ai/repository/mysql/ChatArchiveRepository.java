package com.science.ai.repository.mysql;

import java.util.List;

/**
 * 职责：定义聊天归档仓储抽象，用于将完整消息与会话索引落到 MySQL。
 * 输入：conversationKey(type:chatId)、消息快照、userId。
 * 输出：完成归档写入（幂等语义由实现保证）。
 * 边界条件：当 conversationKey 非法时应快速失败，避免跨业务会话串写。
 */
public interface ChatArchiveRepository {

    /**
     * 职责：归档消息快照到 MySQL。
     * 输入：
     * 1) conversationKey：会话键，格式固定为 type:chatId；
     * 2) messages：待归档消息列表；
     * 3) userId：当前用户标识（未登录场景可传默认值）。
     * 输出：无返回值，异常由调用方决定是否 fail-safe。
     */
    void archiveMessages(String conversationKey, List<ArchiveMessagePayload> messages, String userId);

    /**
     * 职责：幂等保存会话索引。
     * 输入：bizType、chatId、userId。
     * 输出：无返回值，首次写入创建索引，重复写入刷新 lastSavedAt。
     */
    void saveSessionIfAbsentOrUpdate(String bizType, String chatId, String userId);

    /**
     * 职责：承载消息归档所需字段，避免直接暴露 Redis 内部 StoredMessage 结构。
     * 输入输出：仅为数据载体，不包含业务逻辑。
     */
    record ArchiveMessagePayload(
            String messageType,
            String text,
            String createdAtRaw,
            String metadataJson,
            String messageFormat,
            String contentJson,
            String toolName,
            String toolCallId,
            String toolArgumentsJson) {
    }
}

