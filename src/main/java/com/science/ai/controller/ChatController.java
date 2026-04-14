package com.science.ai.controller;
import com.science.ai.repository.ChatHistoryRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 职责：处理通用聊天请求（纯文本/多模态），并把会话上下文写入 ChatMemory。
 * 输入：prompt、chatId、可选文件列表。
 * 输出：按流式方式返回模型生成内容。
 * 边界条件：
 * 1) files 为空时走纯文本链路；
 * 2) files 非空时走多模态链路；
 * 3) 内部会话键统一为 type:chatId，避免不同业务 chatId 冲突。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    /**
     * 通用聊天业务类型常量。
     * 说明：conversationKey 统一使用 {type}:{chatId}，这里固定 type=chat。
     */
    private static final String TYPE = "chat";

    private final ChatClient chatClient;

    private final ChatHistoryRepo redisChatHistoryRepo;

    /**
     * 关键步骤：
     * 1) 先构建统一会话键 conversationKey=type:chatId；
     * 2) 保存 chatId 到历史仓库（仅保留原有行为，不改 ChatHistoryRepo）；
     * 3) 根据是否携带文件分流到纯文本或多模态聊天。
     * 异常处理：参数缺失或模型调用异常沿用现有全局异常处理。
     */
    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(
            @RequestParam("prompt") String prompt,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        // 统一内部会话键：{type}:{chatId}，避免跨业务使用相同 chatId 时发生上下文串话。
        String conversationKey = buildConversationKey(TYPE, chatId);
        // 保存会话 id
        redisChatHistoryRepo.save(TYPE, chatId);

        if (files == null || files.isEmpty()) {
            // 无附件，纯文本聊天
            return textChat(prompt, conversationKey);
        } else {
            // 多模态聊天
            return multiModalChat(prompt, conversationKey, files);
        }

    }

    /**
     * 关键步骤：
     * 1) 将上传文件映射为模型可识别的 Media；
     * 2) 以 conversationKey 传入 ChatMemory，维持同会话上下文。
     * 异常处理：文件 contentType 为空时会触发 NPE，由全局异常处理接管。
     */
    private Flux<String> multiModalChat(String prompt, String conversationKey, List<MultipartFile> files) {
        // 解析 media 文件
        List<Media> medias = files.stream()
                .map(file -> new Media(
                        MimeType.valueOf(Objects.requireNonNull(file.getContentType())),
                        file.getResource())
                )
                .toList();

        return chatClient.prompt()
                // 可变参数是一个同类的数组其实
                .user(p -> p.text(prompt).media(medias.toArray(Media[]::new)))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }

    /**
     * 关键步骤：发送纯文本消息并绑定 conversationKey。
     * 异常处理：模型调用异常按现有全局策略处理。
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
     * 1) 统一组装 conversationKey，格式固定为 {type}:{chatId}；
     * 2) 对 type/chatId 执行 trim，避免请求参数带空白导致同一会话写入不同 Redis key；
     * 3) 保持接口层参数不变，仅在 Controller 内部做规范化。
     * 异常处理：本方法不吞异常，参数异常由调用方/框架处理。
     */
    private String buildConversationKey(String type, String chatId) {
        // trim：消除前后空白，保证会话键稳定。
        String normalizedType = type == null ? "" : type.trim();
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        return normalizedType + ":" + normalizedChatId;
    }

}
