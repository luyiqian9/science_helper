package com.science.ai.strategy;

import com.science.ai.constants.RoleTypeConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 职责：提供顶会编辑角色（editor）策略，重点优化学术英文表达的清晰性与专业性。
 * 输入：chatId、prompt。
 * 输出：编辑视角的英文润色结果文本。
 * 边界条件：
 * 1) 会话键遵循 {type}:{chatId} 规范，这里 type 固定 editor；
 * 2) 通过独立会话 id 保证 editor 与 reviewer 上下文隔离。
 */
@Service
@RequiredArgsConstructor
public class PaperEditorStrategy implements AcademicAgentStrategy {

    private static final String PROMPT_PAPER_EDITOR = """
        你是一位拥有20年经验的顶级期刊（如Nature、Science）及计算机顶会的资深学术编辑。
        你的任务是对用户提供的学术英文文本进行专业润色。

        请严格遵守以下规则：
        1. 消除所有语法错误、生硬表达及“中式英语”。
        2. 提升文本的清晰度、精准度、连贯性，并确保语气符合正式的学术规范。
        3. 绝对保留作者原始的科学含义与逻辑结构。
        4. 不要输出任何寒暄或废话（如“好的，这是润色结果”）。
        5. 排版规则：输出必须极其紧凑！绝对禁止使用双换行符（多余的空行）。列表项之间禁止留空行。

        请严格按照以下紧凑格式输出：
        **[润色结果]**
        (在此处直接输出润色后的英文文本)
        (注意：这里只需要空一行)
        **[修改说明]**
        (必须使用中文简明扼要地列出2-3个核心修改点及原因，帮助作者提升写作水平)
        1. (修改原因1)
        (注意：原因之间绝对不要加空行，紧接着输出下一个原因)
        2. (修改原因2)
        """;

    private final ChatClient chatClient;

    /**
     * 返回当前策略的角色标识，供 AcademicAgentService 做 Map 路由。
     */
    @Override
    public String getRoleType() {
        return RoleTypeConstants.EDITOR;
    }

    /**
     * 关键步骤：
     * 1) 构造 editor 角色独立会话键，避免跨角色上下文污染；
     * 2) 以 PROMPT_PAPER_EDITOR 设定 system prompt；
     * 3) 绑定 ChatMemory.CONVERSATION_ID 后调用模型并返回文本结果。
     * 异常处理：不吞异常，交由上层统一异常处理链路。
     */
    @Override
    public Flux<String> process(String chatId, String prompt) {
        // [AI修改] 角色隔离: 确保不同角色的上下文互不污染。
        String conversationKey = RoleTypeConstants.EDITOR + ":" + (chatId == null ? "" : chatId.trim());

        return chatClient.prompt()
                .system(PROMPT_PAPER_EDITOR)
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }
}

