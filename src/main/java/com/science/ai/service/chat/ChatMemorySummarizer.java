package com.science.ai.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：异步执行聊天记忆滚动摘要压缩，将长历史融合为系统摘要并写回 Redis。
 * 输入：conversationId(type:chatId) 与当前会话消息快照。
 * 输出：当超出阈值时更新 Redis 中对应会话的消息列表，未超限则不做写回。
 * 边界条件：
 * 1) 必须保留末尾最近两条消息（用户问 + 模型答），避免压缩掉当前轮闭环。
 * 2) 仅在最后一条为 AssistantMessage 时压缩，避免半轮对话阶段触发摘要污染语义。
 * 3) 压缩失败采用 fail-safe，仅记录日志，不影响主链路返回。
 */
@Service
@Slf4j
public class ChatMemorySummarizer {

    private static final String KEY_PREFIX = "ai:chat:msgs:";
    private static final int MAX_CHAR_LIMIT = 2500;
    private static final int EXTRACT_COUNT = 10;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatClient summarizerClient;

    public ChatMemorySummarizer(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                @Qualifier("summarizerClient") ChatClient summarizerClient) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.summarizerClient = summarizerClient;
    }

    /**
     * 关键步骤：
     * 1) 容量检查：总字符数未超阈值直接返回；
     * 2) 越界保护：永远保留最近两条消息；
     * 3) 状态判断：若首条为旧摘要(SystemMessage)则一并融合；
     * 4) 调用总结模型生成新摘要；
     * 5) 写回 Redis：删除被压缩片段并将新摘要插入索引 0。
     * 异常处理：任何异常仅记录 error，不抛出到调用线程。
     */
    @Async("chatMemoryCompressExecutor")
    public void compressHistory(String conversationId, List<Message> messages) {
        try {
            // 若消息数小于3则不进行压缩 一开始的消息即使太长也不能立即压缩 因为是当前讨论焦点
            // 此时压缩容易使大模型回答产生严重幻觉
            if (!StringUtils.hasText(conversationId) || messages == null || messages.size() < 3) {
                return;
            }

            // advisor 并发场景下，UserMessage 落库是半闭环；仅当 AssistantMessage 写入后才允许复盘摘要。
            Message latest = messages.get(messages.size() - 1);
            if (!(latest instanceof AssistantMessage)) {
                return;
            }

            int totalChars = calculateTotalChars(messages);
            System.out.println("thread-name" + Thread.currentThread().getName() + " Total characters: " + totalChars);
            if (totalChars <= MAX_CHAR_LIMIT) {
                return;
            }

            // 确保至少保留两条消息（最新轮次的用户问 + 模型答） 若只有两条 也不压缩
            int maxExtractableEnd = messages.size() - 2;
            if (maxExtractableEnd <= 0) {
                return;
            }

            // 如果首条消息是旧摘要，则一并融合
            int extractStart = 0;
            String oldSummary = "";
            if (messages.get(0) instanceof SystemMessage systemMessage) {
                oldSummary = systemMessage.getText();
                extractStart = 1;
            }

            // 计算可提取的消息数量
            int extractableCount = maxExtractableEnd - extractStart;
            if (extractableCount <= 0) {
                return;
            }
            // 实际提取的消息数量不能超过阈值
            int actualExtractCount = Math.min(EXTRACT_COUNT, extractableCount);
            List<Message> extractedMessages = new ArrayList<>(messages.subList(extractStart, extractStart + actualExtractCount));

            String summaryInput = buildSummaryInput(oldSummary, extractedMessages);
            String summary = summarizerClient.prompt()
                                .user(summaryInput)
                                .call()
                                .content();

            // 若生成的摘要为空 则放弃此次压缩 避免丢失前面的记忆
            if (!StringUtils.hasText(summary)) {
                return;
            }

            // 从头部移除被提取的旧摘要+旧对话，仅保留“未提取历史 + 最新两条”。
            int removeEndExclusive = extractStart + actualExtractCount;
            List<Message> compressed = new ArrayList<>(messages.subList(removeEndExclusive, messages.size()));
            SystemMessage newSummary = SystemMessage.builder()
                    .text("前期对话摘要：" + summary.trim())
                    .metadata(Map.of("createdAt", OffsetDateTime.now().toString()))
                    .build();
            compressed.add(0, newSummary);

            overwriteConversation(conversationId, compressed);
        } catch (Exception ex) {
            log.error("Async chat memory summarization failed. conversationId={}", conversationId, ex);
        }
    }

    private int calculateTotalChars(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            if (message != null && message.getText() != null) {
                total += message.getText().length();
            }
        }
        return total;
    }

    private String buildSummaryInput(String oldSummary, List<Message> extractedMessages) {
        StringBuilder dialogBuilder = new StringBuilder();
        for (Message message : extractedMessages) {
            if (message == null) {
                continue;
            }
            // 给出一个示例  [USER] 用户的输入
            //            [ASSISTANT] 模型的输出
            dialogBuilder.append('[')
                    .append(message.getMessageType().name())
                    .append("] ")
                    .append(message.getText() == null ? "" : message.getText())
                    .append('\n');
        }
        return "旧摘要：" + oldSummary + "\n后续对话：\n" + dialogBuilder;
    }

    private void overwriteConversation(String conversationId, List<Message> messages) {
        List<StoredMessage> storedMessages = messages.stream().map(this::toStoredMessage).toList();
        List<String> messageJsonList = new ArrayList<>(storedMessages.size());
        for (StoredMessage storedMessage : storedMessages) {
            try {
                // 序列化：保持 messageType/text/createdAt/metadata 字段结构与主存储一致。
                messageJsonList.add(objectMapper.writeValueAsString(storedMessage));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to serialize summarized messages", ex);
            }
        }

        String redisKey = KEY_PREFIX + normalizeConversationKey(conversationId);
        // 删除旧会话记录
        redisTemplate.delete(redisKey);

        if (!messageJsonList.isEmpty()) {
            // 仅当有消息时才写入
            redisTemplate.opsForList().rightPushAll(redisKey, messageJsonList);
        }
    }

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

    private String normalizeConversationKey(String conversationKey) {
        if (!StringUtils.hasText(conversationKey)) {
            throw new IllegalArgumentException("conversationId must be in format type:chatId");
        }

        int splitIndex = conversationKey.indexOf(':');
        if (splitIndex <= 0 || splitIndex == conversationKey.length() - 1) {
            throw new IllegalArgumentException("conversationId must be in format type:chatId");
        }

        String type = conversationKey.substring(0, splitIndex).trim();
        String chatId = conversationKey.substring(splitIndex + 1).trim();
        if (!StringUtils.hasText(type) || !StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("conversationId must be in format type:chatId");
        }
        return type + ":" + chatId;
    }

    private String extractCreatedAt(Map<String, Object> metadata) {
        Object fromMetadata = metadata.get("createdAt");
        if (fromMetadata != null) {
            return normalizeCreatedAt(String.valueOf(fromMetadata));
        }
        return OffsetDateTime.now().toString();
    }

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

    private record StoredMessage(String messageType, String text, String createdAt, Map<String, Object> metadata) {
    }
}
