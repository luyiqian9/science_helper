package com.science.ai.strategy;

import com.science.ai.constants.RoleTypeConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 职责：提供审稿人角色（reviewer）策略，重点检查论文片段的逻辑严谨性与论证完整性。
 * 输入：chatId、prompt。
 * 输出：审稿视角的批评性评审文本。
 * 边界条件：
 * 1) 会话键遵循 {type}:{chatId} 规范，这里 type 固定 reviewer；
 * 2) 通过独立会话 id 保证 reviewer 与 reviewer 上下文隔离。
 */
@Service
@RequiredArgsConstructor
public class HarshReviewerStrategy implements AcademicAgentStrategy {

    private static final String PROMPT_HARSH_REVIEWER = """
        你是顶级学术会议（如NeurIPS、CVPR）中极其严苛的“审稿人2（Reviewer 2）”。
        你的任务是对用户提供的研究思路、摘要或方法论进行深度的批判性审查。

        请严格遵守以下规则：
        1. 重点评估方案的“合理性（Soundness）”和“清晰度（Clarity）”。
        2. 评价必须直接、严谨、一针见血。绝对不要使用任何客套话或废话。
        3. 排版规则：输出必须极其紧凑！仅使用单换行符。全文绝对禁止出现双换行符（多余的空行）。列表项之间禁止留空行。
        4. 必须全部使用中文输出你的审稿意见。

        请严格按照以下紧凑格式输出：
        **[致命弱点]**
        - (指出具体的逻辑漏洞或实验设计缺陷1)
        - (指出具体的逻辑漏洞或实验设计缺陷2)
        注意 若没有原则性问题 可以直说 不要编造或刻意夸大
        **[尖锐提问]**
        - (提出1个作者必须在Rebuttal中回答的直击痛点的问题)
        注意 若你认为没有问题 就直接说 “没有问题”
        **[建设性建议]**
        - (给出如何弥补上述缺陷的具体实操建议)
        """;

    private final ChatClient chatClient;

    /**
     * 返回当前策略的角色标识，供 AcademicAgentService 做 Map 路由。
     */
    @Override
    public String getRoleType() {
        return RoleTypeConstants.REVIEWER;
    }

    /**
     * 关键步骤：
     * 1) 构造 reviewer 角色独立会话键，避免跨角色上下文污染；
     * 2) 以 PROMPT_HARSH_REVIEWER 设定 system prompt；
     * 3) 绑定 ChatMemory.CONVERSATION_ID 后调用模型并返回文本结果。
     * 异常处理：不吞异常，交由上层统一异常处理链路。
     */
    @Override
    public Flux<String> process(String chatId, String prompt) {
        // [AI修改] 角色隔离: 确保不同角色的上下文互不污染。
        String conversationKey = RoleTypeConstants.REVIEWER + ":" + (chatId == null ? "" : chatId.trim());

        return chatClient.prompt()
                .system(PROMPT_HARSH_REVIEWER)
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationKey))
                .stream()
                .content();
    }
}

