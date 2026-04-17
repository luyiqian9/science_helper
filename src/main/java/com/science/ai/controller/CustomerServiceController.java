package com.science.ai.controller;

import com.science.ai.repository.redis.ChatHistoryRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 职责：处理客服对话请求，并将会话上下文写入统一会话键。
 * 输入：prompt、chatId。
 * 输出：模型流式回复。
 * 边界条件：会话键固定为 service:chatId，避免与 chat/pdf/game 业务冲突。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
public class CustomerServiceController {

    /**
     * 客服业务类型常量。
     * 说明：统一会话键格式为 {type}:{chatId}，这里固定 type=service。
     */
    private static final String TYPE = "service";

    private final ChatClient serviceChatClient;
    private final ChatHistoryRepo chatHistoryRepo;

    /**
     * 关键步骤：
     * 1) 保存 chatId 历史（保持原有 ChatHistoryRepo 行为）；
     * 2) 构建 conversationKey=service:chatId；
     * 3) 将 conversationKey 透传给 ChatMemory。
     * 异常处理：模型调用异常沿用现有全局异常处理。
     */
    @RequestMapping(value = "/service", produces = "text/html;charset=utf-8")
    public Flux<String> service(String prompt, String chatId) {
        chatHistoryRepo.save(TYPE, chatId);
        String conversationKey = buildConversationKey(chatId);

        return serviceChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }

    /**
     * 关键步骤：
     * 1) 使用固定业务类型 TYPE，组装 conversationKey={type}:{chatId}；
     * 2) 对 chatId 做 trim，避免 " 123 " 这类输入导致 key 不一致；
     * 3) 保持外部接口参数不变，仅内部标准化。
     * 异常处理：本方法不吞异常，参数异常由调用方处理。
     */
    private String buildConversationKey(String chatId) {
        // trim：规整会话 id，保障 ChatMemory 读写命中同一 key。
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        return TYPE + ":" + normalizedChatId;
    }

}
