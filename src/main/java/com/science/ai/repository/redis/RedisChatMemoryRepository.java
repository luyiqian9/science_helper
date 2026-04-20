package com.science.ai.repository.redis;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.science.ai.repository.mysql.ChatArchiveRepository;
import com.science.ai.service.archive.ChatArchiveFacadeService;
import com.science.ai.service.chat.ChatMemorySummarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：基于 Redis 实现 Spring AI 的会话记忆仓储，统一使用 conversationKey(type:chatId) 作为会话标识。
 * 输入：conversationId（内部约定为 type:chatId）与消息列表。
 * 输出：返回会话消息列表、会话 id 列表，或完成写入/删除操作。
 * 边界条件：
 * 1) 当 conversationId 不符合 type:chatId 时直接抛出异常，避免跨业务 chatId 冲突。
 * 2) 当 Redis 中不存在消息或数据为空时，返回空列表而不是 null。
 * 3) 反序列化仅保证 USER/ASSISTANT/SYSTEM 三类消息，未知类型会被安全忽略。
 */
@Repository
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "ai:chat:msgs:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatArchiveFacadeService chatArchiveFacadeService;
    private final ChatMemorySummarizer chatMemorySummarizer;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     ChatArchiveFacadeService chatArchiveFacadeService,
                                     ChatMemorySummarizer chatMemorySummarizer) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.chatArchiveFacadeService = chatArchiveFacadeService;
        this.chatMemorySummarizer = chatMemorySummarizer;
    }

    /**
     * 关键步骤：
     * 1) 从 Redis 按统一前缀扫描会话键。
     * 2) 去掉前缀后返回 conversationKey(type:chatId)。
     * 异常处理：Redis 访问异常交由上层统一处理，不做吞异常。
     */
    @Override
    @NonNull
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        return keys.stream().map(key -> key.substring(KEY_PREFIX.length())).toList();
    }

    /**
     * 关键步骤：
     * 1) 根据 conversationKey 计算 Redis key。
     * 2) 从 Redis List 读取全部消息 JSON（按写入顺序）。
     * 3) 逐条反序列化后恢复成 Spring AI Message。
     * 异常处理：
     * - 单条坏数据（非法 JSON/非法 messageType）会被跳过并记录 warn 日志，不影响其它消息读取；
     * - Redis 访问异常继续上抛，沿用全局异常链路。
     */
    @Override
    @NonNull
    public List<Message> findByConversationId(@NonNull String conversationId) {
        String normalizedConversationKey = normalizeConversationKey(conversationId);
        String redisKey = buildRedisKey(normalizedConversationKey);
        // 从 Redis List 读取全部消息 JSON
        List<String> messageJsonList = redisTemplate.opsForList().range(redisKey, 0, -1);
        if (messageJsonList == null || messageJsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Message> result = new ArrayList<>();
        for (int i = 0; i < messageJsonList.size(); i++) {
            String messageJson = messageJsonList.get(i);
            try {
                // 反序列化：每个 list 元素都是一条消息 JSON，逐条恢复。
                StoredMessage storedMessage = objectMapper.readValue(messageJson, StoredMessage.class);
                Message message = toMessage(storedMessage);
                if (message != null) {
                    result.add(message);
                } else {
                    // 容错：messageType 非 USER/ASSISTANT/SYSTEM 时跳过，避免一条脏数据阻断整段历史。
                    log.warn("Skip unsupported redis message. key={}, index={}, raw={}", redisKey, i, messageJson);
                }
            } catch (Exception ex) {
                // 容错：单条 JSON 损坏时仅记录日志并跳过，保留可用历史消息。
                log.warn("Skip broken redis message json. key={}, index={}, raw={}", redisKey, i, messageJson, ex);
            }
        }
        return result;
    }

    /**
     * 关键步骤：
     * 1) 将 Message 序列化为固定字段（messageType/text/createdAt/metadata）。
     * 2) 对完全重复消息进行去重，降低重试写入导致的冗余。
     * 3) 将每条消息序列化为单条 JSON，并按时间顺序 RPUSH 到 Redis List。
     * 4) Redis 写入成功后，按 fail-safe 策略双写 MySQL 归档（异常只记日志，不影响主链路）。
     * 5) 异步触发滚动摘要压缩，避免同步阻塞请求。
     * 异常处理：JSON 序列化失败时抛 IllegalStateException，避免写入不完整数据。
     */
    @Override
    public void saveAll(@NonNull String conversationId, @NonNull List<Message> messages) {
        // 兼容逻辑：优先使用入参 conversationId；当上层未传时，回退到消息 metadata 中的 CONVERSATION_ID。
        String conversationKey = resolveConversationKey(conversationId, messages);
        String redisKey = buildRedisKey(conversationKey);

        if (messages.isEmpty()) {
            redisTemplate.delete(redisKey);
            return;
        }

        // 序列化：将内存中的 Message 转换为可持久化的固定结构。
        List<StoredMessage> serialized = messages.stream().map(this::toStoredMessage).toList();
        // 去重：按消息关键字段去重，避免同一条消息因重试重复入库。
        List<StoredMessage> deduplicated = deduplicate(serialized);

        try {
            // 序列化：每条消息单独转成 JSON，后续按 list 逐条写入。
            List<String> messageJsonList = new ArrayList<>(deduplicated.size());
            for (StoredMessage storedMessage : deduplicated) {
                messageJsonList.add(objectMapper.writeValueAsString(storedMessage));
            }

            // 覆盖写入：saveAll 是窗口全量快照，先删旧 list 再按顺序写新 list。
            redisTemplate.delete(redisKey);

            // 顺序保证：按集合顺序逐条 RPUSH，Redis list 从左到右即时间先后。
            // 相当于redis命令 rpush redisKey messageJsonList
            redisTemplate.opsForList().rightPushAll(redisKey, messageJsonList);

            // 双写顺序：先写 Redis（主链路记忆源），再写 MySQL（完整归档），避免归档成功但记忆缺失。
            archiveToMysqlFailSafe(conversationKey, deduplicated);

/*            for (String messageJson : messageJsonList) {
                 相当于redis命令 rpush redisKey messageJson
                redisTemplate.opsForList().rightPush(redisKey, messageJson);
            }*/

            // 滚雪球摘要算法(Rolling Summarization): 独立Service规避AOP失效，融合历史摘要与最老对话，实现无限上下文记忆。
            // 仅当最后一条为 AssistantMessage（本轮闭环完成）时才触发压缩，避免 UserMessage 半闭环阶段误压缩。
            if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof AssistantMessage) {
                chatMemorySummarizer.compressHistory(conversationKey, new ArrayList<>(messages));
            }

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat messages to redis", e);
        }
    }

    /**
     * 关键步骤：
     * 1) 将 StoredMessage 转换为归档载荷；
     * 2) 调用归档仓储写入 MySQL。
     * 异常处理：采用 fail-safe 策略，归档异常只记录 error，不中断聊天主流程。
     */
    private void archiveToMysqlFailSafe(String conversationKey, List<StoredMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        // 门面接管归档策略：统一处理开关、默认 userId 与 fail-safe，仓储层只负责数据转换和调用。
        List<ChatArchiveRepository.ArchiveMessagePayload> payloads = toArchivePayloads(messages);
        chatArchiveFacadeService.archiveMessagesFailSafe(conversationKey, payloads);
    }

    /**
     * 关键步骤：
     * 1) metadata 序列化为 JSON，保留未来扩展字段；
     * 2) 生成 contentJson 结构，兼容后续多模态/content 扩展；
     * 3) tool 相关字段先从 metadata 中提取，未命中时保持 null。
     * 异常处理：JSON 序列化失败抛 IllegalStateException，避免写入不一致归档数据。
     */
    private List<ChatArchiveRepository.ArchiveMessagePayload> toArchivePayloads(List<StoredMessage> messages) {
        List<ChatArchiveRepository.ArchiveMessagePayload> payloads = new ArrayList<>(messages.size());
        for (StoredMessage message : messages) {
            try {
                String metadataJson = objectMapper.writeValueAsString(
                        message.metadata() == null ? Collections.emptyMap() : message.metadata());

                Map<String, Object> content = new LinkedHashMap<>();
                content.put("text", message.text());
                // 序列化 contentJson：即使当前仅文本，也统一结构化存储，方便未来多模态扩展。
                String contentJson = objectMapper.writeValueAsString(content);

                Map<String, Object> metadata = message.metadata() == null
                        ? Collections.emptyMap()
                        : message.metadata();

                payloads.add(new ChatArchiveRepository.ArchiveMessagePayload(
                        message.messageType(),
                        message.text(),
                        message.createdAt(),
                        metadataJson,
                        "TEXT",
                        contentJson,
                        metadata.get("toolName") == null ? null : String.valueOf(metadata.get("toolName")),
                        metadata.get("toolCallId") == null ? null : String.valueOf(metadata.get("toolCallId")),
                        metadata.get("toolArgumentsJson") == null ? null : String.valueOf(metadata.get("toolArgumentsJson"))
                ));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to serialize archive payload", ex);
            }
        }
        return payloads;
    }

    /**
     * 关键步骤：根据会话键直接删除对应 Redis 记录。
     * 异常处理：删除失败异常交由上层处理，不吞异常。
     */
    @Override
    public void deleteByConversationId(@NonNull String conversationId) {
        redisTemplate.delete(buildRedisKey(conversationId));
    }

    /**
     * 关键步骤：根据规范化后的 conversationKey 组装 Redis key。
     * 异常处理：conversationKey 格式不合法时抛 IllegalArgumentException。
     */
    private String buildRedisKey(String conversationKey) {
        return KEY_PREFIX + normalizeConversationKey(conversationKey);
    }

    /**
     * 关键步骤：
     * 1) 优先使用方法入参 conversationId；
     * 2) 入参为空时，兼容从消息 metadata 读取 ChatMemory.CONVERSATION_ID；
     * 3) 最终统一规范为 type:chatId。
     * 异常处理：两种来源都无法得到合法键时抛 IllegalArgumentException。
     */
    private String resolveConversationKey(String conversationId, List<Message> messages) {
        if (StringUtils.hasText(conversationId)) {
            return normalizeConversationKey(conversationId);
        }

        for (Message message : messages) {
            if (message == null || message.getMetadata() == null) {
                continue;
            }
            Object metadataConversationId = message.getMetadata().get(ChatMemory.CONVERSATION_ID);
            if (metadataConversationId == null) {
                continue;
            }
            String fromMetadata = String.valueOf(metadataConversationId);
            if (StringUtils.hasText(fromMetadata)) {
                // 兼容逻辑：读取 metadata 中的 conversationKey，保障历史数据仍可正确写入。
                return normalizeConversationKey(fromMetadata);
            }
        }

        throw new IllegalArgumentException("conversationId must be in format type:chatId");
    }

    /**
     * 关键步骤：
     * 1) 对输入做 trim，避免前后空白导致 key 分裂；
     * 2) 仅接受 type:chatId 结构，且 type/chatId 都不能为空。
     * 异常处理：格式不合法时抛 IllegalArgumentException。
     */
    private String normalizeConversationKey(String conversationKey) {
        if (!StringUtils.hasText(conversationKey)) {
            throw new IllegalArgumentException("conversationId must be in format type:chatId");
        }

        int splitIndex = conversationKey.indexOf(':');
        if (splitIndex <= 0 || splitIndex == conversationKey.length() - 1) {
            throw new IllegalArgumentException("conversationId must be in format type:chatId");
        }

        // trim：分别清理 type/chatId 两侧空白，确保 Redis key 始终稳定一致。
        String type = conversationKey.substring(0, splitIndex).trim();
        String chatId = conversationKey.substring(splitIndex + 1).trim();
        if (!StringUtils.hasText(type) || !StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("conversationId must be in format type:chatId");
        }
        return type + ":" + chatId;
    }

    // 从 Message 转换为 StoredMessage
    private StoredMessage toStoredMessage(Message message) {
        Map<String, Object> metadata = message.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(message.getMetadata());

        String createdAt = extractCreatedAt(metadata);
        metadata.putIfAbsent("createdAt", createdAt);

        return new StoredMessage(
                message.getMessageType().name(),
                message.getText(),
                createdAt,
                metadata
        );
    }

    // 从 StoredMessage 转换为 Message
    private Message toMessage(StoredMessage storedMessage) {
        if (storedMessage == null || !StringUtils.hasText(storedMessage.messageType())) {
            return null;
        }

        String text = storedMessage.text() == null ? "" : storedMessage.text();
        Map<String, Object> metadata = storedMessage.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(storedMessage.metadata());
        metadata.putIfAbsent("createdAt", normalizeCreatedAt(storedMessage.createdAt()));

        MessageType type;
        try {
            type = MessageType.valueOf(storedMessage.messageType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }

        // 反序列化：至少支持 USER/ASSISTANT/SYSTEM，未知类型忽略避免阻断读取。
        return switch (type) {
            case USER -> UserMessage.builder().text(text).metadata(metadata).build();
            case ASSISTANT -> AssistantMessage.builder().content(text).properties(metadata).build();
            case SYSTEM -> SystemMessage.builder().text(text).metadata(metadata).build();
            default -> null;
        };
    }

    // 从 metadata 中提取 createdAt 字段，如果不存在则使用当前时间
    private String extractCreatedAt(Map<String, Object> metadata) {
        Object fromMetadata = metadata.get("createdAt");
        if (fromMetadata != null) {
            return normalizeCreatedAt(String.valueOf(fromMetadata));
        }
        return OffsetDateTime.now().toString();
    }

    // 规范化 createdAt 字段，确保其为 OffsetDateTime 格式
    private String normalizeCreatedAt(String createdAt) {
        if (!StringUtils.hasText(createdAt)) {
            return OffsetDateTime.now().toString();
        }
        try {
            return OffsetDateTime.parse(createdAt.trim()).toString();
        } catch (DateTimeParseException ex) {
            return OffsetDateTime.now().toString();
        }
    }

    // 去重：按消息类型、文本内容和创建时间去重
    private List<StoredMessage> deduplicate(List<StoredMessage> source) {
        if (source.isEmpty()) {
            return source;
        }

        List<StoredMessage> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StoredMessage message : source) {
            String uniqueKey = message.messageType() + "|" + message.text() + "|" + message.createdAt();
            if (seen.add(uniqueKey)) {
                result.add(message);
            }
        }
        return result;
    }


    // 存储消息的记录类
    private record StoredMessage(String messageType, String text, String createdAt, Map<String, Object> metadata) {
    }

}
