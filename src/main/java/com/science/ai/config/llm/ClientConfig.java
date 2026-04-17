package com.science.ai.config.llm;

import com.science.ai.model.AlibabaOpenAiChatModel;
import com.science.ai.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClientConfig {
    /**
     * 职责：创建统一的会话记忆组件，底层持久化到 Redis。
     * 输入：RedisChatMemoryRepository（由 Spring 注入）。
     * 输出：ChatMemory 实例，保留最近 20 条消息。
     * 边界条件：当会话消息超过 20 条时自动裁剪窗口，避免上下文无限增长。
     */
    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)  // 设置保留最近20条消息
                .chatMemoryRepository(redisChatMemoryRepository)
                .build();
    }

    @Bean
    public ChatClient chatClient(AlibabaOpenAiChatModel model, ChatMemory chatMemory) {
        return ChatClient.builder(model)
//                .defaultOptions(ChatOptions.builder().model("qwen-omni-turbo").build())
                .defaultSystem("你是可爱的小助手 名字叫小团团")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 配置会话记忆
                        new SimpleLoggerAdvisor(1)  // 配置日志
                )
                .build();
    }

    @Bean
    public ChatClient pdfChatClient(OpenAiChatModel model, ChatMemory chatMemory, VectorStore vectorStore) {
        SearchRequest searchRequest = SearchRequest.builder()
                .similarityThreshold(0.5d)
                .topK(2)
                .build();

        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor
                .builder(vectorStore)
                .searchRequest(searchRequest)
                .build();

        return ChatClient.builder(model)
                .defaultSystem("根据上下文回答问题，遇到上下文没有的问题，不要随意编造。")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        ragAdvisor,
                        new SimpleLoggerAdvisor(1)
                )
                .build();
    }
}
