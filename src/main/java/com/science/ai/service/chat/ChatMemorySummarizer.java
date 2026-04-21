package com.science.ai.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private static final String COMPRESS_LOCK_PREFIX = "ai:chat:lock:compress:";
    private static final String WRITE_LOCK_PREFIX = "ai:chat:lock:write:";
    private static final int MAX_CHAR_LIMIT = 500;
    private static final int EXTRACT_COUNT = 10;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatClient summarizerClient;
    private final RedissonClient redissonClient;

    public ChatMemorySummarizer(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                @Qualifier("summarizerClient") ChatClient summarizerClient,
                                RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.summarizerClient = summarizerClient;
        this.redissonClient = redissonClient;
    }

    /**
     * 关键步骤：
     * 1) 使用会话级压缩锁，避免同一会话并发触发多个摘要线程；
     * 2) 从 Redis 读取最新快照并判断是否达到压缩阈值；
     * 3) 生成摘要后，再次读取最新快照进行合并；
     * 4) 使用会话级写锁覆写 Redis，避免与 saveAll 并发写互相覆盖。
     * 异常处理：任何异常仅记录 error，不抛出到调用线程。
     */
    @Async("chatMemoryCompressExecutor")
    public void compressHistory(String conversationId, List<Message> messages) {
        String normalizedConversationKey = normalizeConversationKey(conversationId);
        RLock compressLock = redissonClient.getLock(buildCompressLockKey(normalizedConversationKey));
        boolean locked = false;
        try {
            // 压缩锁：同一会话同一时刻只允许一个压缩线程执行，避免重复摘要和覆盖写。
            locked = compressLock.tryLock(0, 15, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("Skip compression because another compressor is running. conversationId={}", normalizedConversationKey);
                return;
            }

            List<Message> latestMessages = loadMessagesFromRedis(normalizedConversationKey);
            CompressionPlan firstPlan = buildCompressionPlan(latestMessages);
            if (firstPlan == null) {
                return;
            }

            String summaryInput = buildSummaryInput(firstPlan.oldSummary(), firstPlan.extractedMessages());
            String summary = summarizerClient.prompt()
                                .user(summaryInput)
                                .call()
                                .content();

            // 若无摘要输出则证明大模型调用失败 不应将聊天记录空覆盖
            if (!StringUtils.hasText(summary)) {
                return;
            }

            // 重新拉取最新 Redis 快照：避免直接用旧快照写回导致新消息被覆盖。
            List<Message> latestBeforeWrite = loadMessagesFromRedis(normalizedConversationKey);
            // TODO 乐观再校验
            boolean suc = checkPlan(latestBeforeWrite);
            if (!suc) {
                return;
            }

            List<Message> compressed = buildCompressedMessages(
                    latestBeforeWrite,
                    firstPlan.extractStart(),
                    firstPlan.actualExtractCount(),
                    summary
            );

            // 会话写锁：与 saveAll 共用锁，确保同会话 Redis 写入串行。
            RLock writeLock = redissonClient.getLock(buildWriteLockKey(normalizedConversationKey));
            boolean writeLocked = false;
            try {
                writeLocked = writeLock.tryLock(2, 10, TimeUnit.SECONDS);
                if (!writeLocked) {
                    log.warn("Skip compressed write-back due to lock timeout. conversationId={}", normalizedConversationKey);
                    return;
                }

                overwriteConversation(normalizedConversationKey, compressed);

            } finally {
                if (writeLocked && writeLock.isHeldByCurrentThread()) {
                    writeLock.unlock();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring compression lock. conversationId={}", normalizedConversationKey, ex);
        } catch (Exception ex) {
            log.error("Async chat memory summarization failed. conversationId={}", normalizedConversationKey, ex);
        } finally {
            if (locked && compressLock.isHeldByCurrentThread()) {
                compressLock.unlock();
            }
        }
    }

    private boolean checkPlan(List<Message> latestBeforeWrite) {
        return true;
    }

    /**
     * 关键步骤：
     * 1) 从 Redis list 读取会话消息 JSON；
     * 2) 逐条反序列化为 StoredMessage；
     * 3) 转换为 Spring AI Message 并跳过坏数据。
     * 异常处理：单条坏数据跳过并记录日志，整体读取不中断。
     */
    private List<Message> loadMessagesFromRedis(String conversationId) {
        String redisKey = KEY_PREFIX + conversationId;
        List<String> messageJsonList = redisTemplate.opsForList().range(redisKey, 0, -1);
        if (messageJsonList == null || messageJsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Message> result = new ArrayList<>(messageJsonList.size());
        for (int i = 0; i < messageJsonList.size(); i++) {
            try {
                StoredMessage stored = objectMapper.readValue(messageJsonList.get(i), StoredMessage.class);
                Message message = toMessage(stored);
                if (message != null) {
                    result.add(message);
                }
            } catch (Exception ex) {
                log.warn("Skip broken message while loading conversation snapshot. conversationId={}, index={}", conversationId, i, ex);
            }
        }
        return result;
    }

    /**
     * 关键步骤：
     * 1) 做容量检查与闭环检查；
     * 2) 保留末尾最新两条消息；
     * 3) 判断首条是否为旧摘要并计算抽取区间。
     * 异常处理：不满足压缩条件时返回 null。
     */
    private CompressionPlan buildCompressionPlan(List<Message> messages) {
        // 若从redis里只查到两条消息 为当前的讨论热点 不宜压缩 等待后续聊天来 这个成为历史再压缩
        if (messages == null || messages.size() < 3) {
            return null;
        }

        // 确保最新消息为 AssistantMessage 只有第二次来才压缩
        Message latest = messages.get(messages.size() - 1);
        if (!(latest instanceof AssistantMessage)) {
            return null;
        }

        // 计算总字符数，超过阈值才进行压缩
        int totalChars = calculateTotalChars(messages);
        log.debug("Total chars: {} for ThreadName {}", totalChars, Thread.currentThread().getName());
        if (totalChars <= MAX_CHAR_LIMIT) {
            return null;
        }

        // 计算最大可抽取的结束位置，确保至少保留两条消息
        int maxExtractableEnd = messages.size() - 2;
        if (maxExtractableEnd <= 0) {
            return null;
        }

        int extractStart = 0;
        String oldSummary = "";
        if (messages.get(0) instanceof SystemMessage systemMessage) {
            oldSummary = systemMessage.getText();
            extractStart = 1;
        }

        int extractableCount = maxExtractableEnd - extractStart;
        // 若消息条数为3 只多出一条摘要 也不压缩
        if (extractableCount <= 0) {
            return null;
        }

        int actualExtractCount = Math.min(EXTRACT_COUNT, extractableCount);
        List<Message> extractedMessages = new ArrayList<>(
                messages.subList(extractStart, extractStart + actualExtractCount));

        return new CompressionPlan(extractStart, actualExtractCount, oldSummary, extractedMessages);
    }

    /**
     * 关键步骤：
     * 1) 基于当前最新快照移除抽取片段；
     * 2) 将新摘要放在索引 0 作为新的系统摘要；
     * 3) 保留未抽取历史与最近两条闭环消息。
     * 异常处理：参数异常时由上层调用方保证，方法内部不吞异常。
     */
    private List<Message> buildCompressedMessages(List<Message> latestMessages,
                                                  int extractStart,
                                                  int actualExtractCount,
                                                  String summary) {
        int removeEndExclusive = extractStart + actualExtractCount;
        List<Message> compressed = new ArrayList<>(latestMessages.subList(removeEndExclusive, latestMessages.size()));

        SystemMessage newSummary = SystemMessage.builder()
                .text("前期对话摘要：" + summary.trim())
                .metadata(Map.of("createdAt", OffsetDateTime.now().toString()))
                .build();
        compressed.add(0, newSummary);

        return compressed;
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

        return switch (type) {
            case USER -> UserMessage.builder().text(text).metadata(metadata).build();
            case ASSISTANT -> AssistantMessage.builder().content(text).properties(metadata).build();
            case SYSTEM -> SystemMessage.builder().text(text).metadata(metadata).build();
            default -> null;
        };
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

    private String buildCompressLockKey(String conversationKey) {
        return COMPRESS_LOCK_PREFIX + normalizeConversationKey(conversationKey);
    }

    private String buildWriteLockKey(String conversationKey) {
        return WRITE_LOCK_PREFIX + normalizeConversationKey(conversationKey);
    }

    private record CompressionPlan(int extractStart,
                                   int actualExtractCount,
                                   String oldSummary,
                                   List<Message> extractedMessages) {
    }

    private record StoredMessage(String messageType, String text, String createdAt, Map<String, Object> metadata) {
    }
}
