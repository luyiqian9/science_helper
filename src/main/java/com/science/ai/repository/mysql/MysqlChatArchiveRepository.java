package com.science.ai.repository.mysql;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责：基于 MySQL 实现聊天完整归档，覆盖消息归档与会话索引 upsert。
 * 输入：conversationKey(type:chatId)、消息列表、userId。
 * 输出：向 chat_message_archive/chat_session_index 持久化幂等数据。
 * 边界条件：
 * 1) conversationKey 必须可解析为 type:chatId；
 * 2) 指纹重复消息不重复插入；
 * 3) userId 允许使用默认值（anonymous）以兼容当前无登录体系。
 */
@Repository
@RequiredArgsConstructor
public class MysqlChatArchiveRepository implements ChatArchiveRepository {

    private static final String UPSERT_MESSAGE_SQL = """
            INSERT INTO chat_message_archive (
                biz_type,
                chat_id,
                user_id,
                message_type,
                message_format,
                text_content,
                content_json,
                created_at_raw,
                metadata_json,
                tool_name,
                tool_call_id,
                tool_args_json,
                msg_fingerprint,
                saved_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                saved_at = VALUES(saved_at)
            """;

    private static final String UPSERT_SESSION_SQL = """
            INSERT INTO chat_session_index (
                biz_type,
                user_id,
                chat_id,
                first_saved_at,
                last_saved_at
            ) VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                last_saved_at = VALUES(last_saved_at)
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 关键步骤：
     * 1) 解析 conversationKey 得到 bizType/chatId，保证跨业务 chatId 不冲突；
     * 2) 逐条计算 fingerprint，利用唯一键实现幂等去重；
     * 3) 逐条 upsert 到 chat_message_archive，保留完整历史。
     * 异常处理：参数非法时抛 IllegalArgumentException；SQL 异常保持上抛，由调用方决定是否 fail-safe。
     */
    @Override
    public void archiveMessages(String conversationKey, List<ArchiveMessagePayload> messages, String userId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        ConversationKeyParts keyParts = parseConversationKey(conversationKey);
        String normalizedUserId = normalizeUserId(userId);
        LocalDateTime now = LocalDateTime.now();

        for (ArchiveMessagePayload payload : messages) {
            // 关键兼容：建表中的多个字段为 NOT NULL，且当前 SQL 显式写入了这些列。
            // 当显式传入 null 时，数据库不会回退到 DEFAULT，而是直接触发约束异常。
            String normalizedMessageType = defaultIfBlank(payload.messageType(), "UNKNOWN");
            String normalizedMessageFormat = defaultIfBlank(payload.messageFormat(), "DEFAULT");
            String normalizedCreatedAtRaw = safe(payload.createdAtRaw());
            String normalizedContentJson = defaultJson(payload.contentJson());
            String normalizedMetadataJson = defaultJson(payload.metadataJson());
            String normalizedToolName = safe(payload.toolName());
            String normalizedToolCallId = safe(payload.toolCallId());
            String normalizedToolArgsJson = defaultJson(payload.toolArgumentsJson());

            // fingerprint 去重：窗口快照会反复包含旧消息，这里用稳定指纹保证 MySQL 只保留一份。
            String msgFingerprint = buildFingerprint(normalizedMessageType, payload.text(), normalizedCreatedAtRaw);
            jdbcTemplate.update(
                    UPSERT_MESSAGE_SQL,
                    keyParts.bizType(),
                    keyParts.chatId(),
                    normalizedUserId,
                    normalizedMessageType,
                    normalizedMessageFormat,
                    payload.text(),
                    normalizedContentJson,
                    normalizedCreatedAtRaw,
                    normalizedMetadataJson,
                    normalizedToolName,
                    normalizedToolCallId,
                    normalizedToolArgsJson,
                    msgFingerprint,
                    now
            );
        }
    }

    /**
     * 关键步骤：
     * 1) 首次写入时初始化 firstSavedAt/lastSavedAt；
     * 2) 重复写入通过 upsert 仅刷新 lastSavedAt。
     * 异常处理：参数非法时抛 IllegalArgumentException；数据库异常保持上抛。
     */
    @Override
    public void saveSessionIfAbsentOrUpdate(String bizType, String chatId, String userId) {
        if (!StringUtils.hasText(bizType) || !StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("bizType and chatId must not be blank");
        }

        // chat_session_index 的 first_saved_at/last_saved_at 字段是 BIGINT(毫秒)，这里必须传毫秒时间戳。
        long nowMillis = System.currentTimeMillis();
        String normalizedBizType = bizType.trim();
        String normalizedChatId = chatId.trim();
        String normalizedUserId = normalizeUserId(userId);
        jdbcTemplate.update(
                UPSERT_SESSION_SQL,
                normalizedBizType,
                normalizedUserId,
                normalizedChatId,
                nowMillis,
                nowMillis
        );
    }

    /**
     * 关键步骤：
     * 1) 按首个冒号切分 conversationKey，避免 chatId 中包含冒号时被错误截断；
     * 2) 对 type/chatId 执行 trim，确保路由与落库键稳定。
     * 异常处理：不符合 type:chatId 规范时抛 IllegalArgumentException。
     */
    private ConversationKeyParts parseConversationKey(String conversationKey) {
        if (!StringUtils.hasText(conversationKey)) {
            throw new IllegalArgumentException("conversationKey must be in format type:chatId");
        }

        int splitIndex = conversationKey.indexOf(':');
        if (splitIndex <= 0 || splitIndex == conversationKey.length() - 1) {
            throw new IllegalArgumentException("conversationKey must be in format type:chatId");
        }

        // conversationKey 解析：严格拆分为业务类型和会话 id，避免跨业务 chatId 混写。
        String bizType = conversationKey.substring(0, splitIndex).trim();
        String chatId = conversationKey.substring(splitIndex + 1).trim();
        if (!StringUtils.hasText(bizType) || !StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("conversationKey must be in format type:chatId");
        }
        return new ConversationKeyParts(bizType, chatId);
    }

    /**
     * 关键步骤：
     * 1) 按 messageType|text|createdAtRaw 拼接稳定输入；
     * 2) 计算 SHA-256 作为去重指纹。
     * 为什么这样做：该组合在窗口重放场景下保持稳定，可准确识别同一条消息。
     */
    private String buildFingerprint(String messageType, String text, String createdAtRaw) {
        String raw = safe(messageType) + "|" + safe(text) + "|" + safe(createdAtRaw);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId.trim() : "anonymous";
    }

    /**
     * 作用：对需要保底默认值的字符串字段执行归一化。
     * 为什么这样做：当 SQL 显式插入某列且参数为 null 时，MySQL 不会使用列默认值，会直接触发 NOT NULL 约束。
     */
    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    /**
     * 作用：保证 JSON 列永远写入合法非空 JSON 文本。
     * 为什么这样做：content_json/metadata_json/tool_args_json 为 NOT NULL JSON，传 null 会违反约束。
     */
    private String defaultJson(String jsonValue) {
        return StringUtils.hasText(jsonValue) ? jsonValue : "{}";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private record ConversationKeyParts(String bizType, String chatId) {
    }
}

