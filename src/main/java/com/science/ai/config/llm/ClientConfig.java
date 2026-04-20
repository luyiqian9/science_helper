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
     * 输出：ChatMemory 实例。
     * 边界条件：滚动摘要负责压缩历史，这里不再依赖普通截断丢弃关键上下文。
     */
    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)  // 设置保留最近20条消息
                .chatMemoryRepository(redisChatMemoryRepository)
                .build();
    }

    /**
     * 职责：提供无状态摘要客户端，仅用于历史压缩总结。
     * 输入：基础模型。
     * 输出：名为 summarizerClient 的 ChatClient Bean，供异步摘要服务定向注入。
     * 边界条件：不挂载 memory advisor，避免摘要链路污染主会话记忆。
     */
    @Bean("summarizerClient")
    public ChatClient summarizerClient(AlibabaOpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem(
                        "你是专属的后台对话压缩引擎。你的任务是将传入的【前期摘要】与【新增对话】无缝融合。\n" +
                                "【提炼要求】：在绝对不改变事实的前提下，提取核心背景、关键约束、已确认结论与未决问题。\n" +
                                "【严格约束】：\n" +
                                "1. 字数必须严格控制在 300 字以内。\n" +
                                "2. 采用客观的第三方陈述口吻。\n" +
                                "3. 直接输出摘要正文！绝对禁止输出“好的”、“这是摘要”等任何开场白、废话或过渡性语言。"
                )
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
