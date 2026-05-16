package com.science.ai.service.agent.impl;

import com.science.ai.repository.redis.ChatHistoryRepo;
import com.science.ai.service.agent.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private static final String TYPE = "service";

    private final ChatClient agentChatClient;
    private final ChatHistoryRepo chatHistoryRepo;

    @Override
    public Flux<String> service(String prompt, String chatId) {
        chatHistoryRepo.save(TYPE, chatId);
        String conversationKey = buildConversationKey(chatId);

        return agentChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }

    private String buildConversationKey(String chatId) {
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        return TYPE + ":" + normalizedChatId;
    }
}
