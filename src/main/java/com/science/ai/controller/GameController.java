package com.science.ai.controller;

import com.science.ai.repository.ChatHistoryRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 职责：处理游戏场景聊天请求，并将会话上下文写入统一会话键。
 * 输入：prompt、chatId。
 * 输出：模型流式回复。
 * 边界条件：会话键固定为 game:chatId，避免与其它业务 chatId 冲突。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class GameController {

    /**
     * 游戏聊天业务类型常量。
     * 说明：统一会话键格式为 {type}:{chatId}，这里固定 type=game。
     */
    private static final String TYPE = "game";

    private final ChatClient gameChatClient;

    private final ChatHistoryRepo redisChatHistoryRepo;

    /**
     * 关键步骤：
     * 1) 构建 conversationKey=game:chatId；
     * 2) 将 conversationKey 传入 ChatMemory.CONVERSATION_ID；
     * 3) 以流式方式返回模型输出。
     * 异常处理：模型调用异常沿用现有全局异常处理链路。
     */
    @RequestMapping(value = "/game", produces = "text/html;charset=utf-8")
    public Flux<String> game(String prompt, String chatId) {
        String conversationKey = buildConversationKey(TYPE, chatId);

        // 保存聊天记录
//        redisChatHistoryRepo.save(TYPE, chatId);

        return gameChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }

    /**
     * 关键步骤：
     * 1) 使用固定业务类型 TYPE，组装 conversationKey={type}:{chatId}；
     * 2) 对 chatId 做 trim，避免空白字符导致同一会话产生不同 key。
     * 异常处理：本方法不吞异常，参数异常由调用方处理。
     */
    private String buildConversationKey(String type, String chatId) {
        // trim：消除前后空白，保证会话键稳定。
        String normalizedType = type == null ? "" : type.trim();
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        return normalizedType + ":" + normalizedChatId;
    }

}
