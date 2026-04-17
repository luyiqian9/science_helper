package com.science.ai.service.chat.impl;

import com.science.ai.repository.redis.ChatHistoryRepo;
import com.science.ai.service.chat.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 职责：承接 ChatController 下沉的聊天业务逻辑，统一完成会话键构造、历史保存、模型调用分流。
 * 输入：业务类型 type、用户输入 prompt、会话 id(chatId)、可选附件 files。
 * 输出：保持流式响应 Flux<String>，确保接口层行为不变。
 * 边界条件：
 * 1) conversationKey 固定为 {type}:{chatId}，并在内部做 trim 规范化；
 * 2) files 为空或空集合走纯文本链路，files 非空走多模态链路；
 * 3) file.getContentType() 为空时保持现有 NPE 语义，不额外吞异常。
 */
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    private final ChatHistoryRepo chatHistoryRepo;

    /**
     * 关键步骤：
     * 1) 先构建统一会话键 conversationKey，避免不同业务共用 chatId 发生上下文串话；
     * 2) 再保存会话历史，保留 save(type, chatId) 既有契约，确保历史查询行为不变；
     * 3) 最后根据 files 是否为空分流到纯文本/多模态调用。
     * 异常处理：沿用现有全局异常策略，不在 service 层做吞异常处理。
     */
    @Override
    public Flux<String> chat(String type, String prompt, String chatId, List<MultipartFile> files) {
        String conversationKey = buildConversationKey(type, chatId);
        saveChatHistory(type, chatId);

        if (files == null || files.isEmpty()) {
            return textChat(prompt, conversationKey);
        }
        return multiModalChat(prompt, conversationKey, files);
    }

    /**
     * 关键步骤：
     * 1) 统一组装 conversationKey，规则固定为 {type}:{chatId}；
     * 2) 对 type/chatId 执行 trim，避免请求参数包含前后空白导致同一会话写入不同 key。
     * 异常处理：本方法不吞异常，参数异常由调用方/框架统一处理。
     */
    private String buildConversationKey(String type, String chatId) {
        String normalizedType = type == null ? "" : type.trim();
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        return normalizedType + ":" + normalizedChatId;
    }

    /**
     * 关键步骤：
     * 1) 调用 chatHistoryRepo.save(type, chatId) 记录会话 id；
     * 2) 保持传入原始 chatId（不改契约），确保与现有历史数据行为一致。
     * 异常处理：仓储异常交由上层统一处理，不在此吞异常。
     */
    private void saveChatHistory(String type, String chatId) {
        chatHistoryRepo.save(type, chatId);
    }

    /**
     * 关键步骤：
     * 1) 使用纯文本用户输入构造 prompt；
     * 2) 通过 advisors 绑定 ChatMemory.CONVERSATION_ID，保证上下文连续。
     * 为什么这样做：保持与既有链路相同的会话记忆绑定方式，避免响应行为回归。
     * 异常处理：模型调用异常按现有全局异常策略处理。
     */
    private Flux<String> textChat(String prompt, String conversationKey) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }

    /**
     * 关键步骤：
     * 1) 先将附件转换为 Media 列表，确保模型能识别多模态输入；
     * 2) 在同一 conversationKey 下发起调用，维持与纯文本相同的上下文会话。
     * 为什么这样做：把多模态输入准备与调用编排聚合在 service，Controller 仅保留协议职责。
     * 异常处理：文件 contentType 为空时维持 NPE 行为，由全局异常处理接管。
     */
    private Flux<String> multiModalChat(String prompt, String conversationKey, List<MultipartFile> files) {
        List<Media> medias = toMedias(files);

        return chatClient.prompt()
                .user(p -> p.text(prompt).media(medias.toArray(Media[]::new)))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }

    /**
     * 关键步骤：
     * 1) 将 MultipartFile 逐个映射为 Media；
     * 2) 使用 Objects.requireNonNull 保留原有空 contentType 的异常语义。
     * 为什么这样做：保证历史行为兼容，不在迁移阶段引入新的容错分支导致行为变化。
     * 异常处理：contentType 为空触发 NPE，由现有全局异常处理流程处理。
     */
    private List<Media> toMedias(List<MultipartFile> files) {
        return files.stream()
                .map(file -> new Media(
                        MimeType.valueOf(Objects.requireNonNull(file.getContentType())),
                        file.getResource())
                )
                .toList();
    }
}

