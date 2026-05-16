package com.science.ai.controller;

import com.science.ai.service.agent.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 职责：基于 ReAct 模式的 agent
 * 输入：prompt、chatId。
 * 输出：模型流式回复。
 * 边界条件：会话键固定为 service:chatId，避免与 chat/pdf/game 业务冲突。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
public class AgentServiceController {

    private final AgentService agentService;

    /**
     * 关键步骤：
     * 1) 保存 chatId 历史（保持原有 ChatHistoryRepo 行为）；
     * 2) 构建 conversationKey=service:chatId；
     * 3) 将 conversationKey 透传给 ChatMemory。
     * 异常处理：模型调用异常沿用现有全局异常处理。
     */
    @RequestMapping(value = "/service", produces = "text/html;charset=utf-8")
    public Flux<String> service(String prompt, String chatId) {
        return agentService.service(prompt, chatId);
    }

}
