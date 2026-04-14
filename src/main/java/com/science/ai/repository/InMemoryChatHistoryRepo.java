package com.science.ai.repository;

import org.springframework.stereotype.Component;

import java.util.*;

//@Component
public class InMemoryChatHistoryRepo implements ChatHistoryRepo{

    private final Map<String, List<String>> chatHistory = new HashMap<>();

    @Override
    public void save(String type, String chatId) {
/*        if (!chatHistory.containsKey(type)) {
            chatHistory.put(type, Collections.emptyList());
        }
        List<String> chatIds = chatHistory.get(type);*/

        List<String> chatIds = chatHistory.computeIfAbsent(type, k -> new ArrayList<>());

        if (chatIds.contains(chatId)) {
            return;
        }
        chatIds.add(chatId);
    }

    @Override
    public List<String> queryChatIds(String type) {
        /*List<String> chatIds = chatHistory.get(type);
        return chatIds == null ? Collections.emptyList() : chatIds;*/

        return chatHistory.getOrDefault(type, Collections.emptyList());
    }
}
