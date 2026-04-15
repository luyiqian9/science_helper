package com.science.ai.strategy;

import reactor.core.publisher.Flux;

/**
 * 职责：定义学术角色 Agent 的统一策略接口，屏蔽不同角色实现细节。
 * 输入：
 * 1) chatId：当前会话标识，用于构建角色隔离的上下文；
 * 2) prompt：用户输入的论文片段或润色需求文本。
 * 输出：角色对应的大模型文本结果。
 * 边界条件：
 * 1) 角色标识由 getRoleType() 提供，供上层路由服务进行动态分发；
 * 2) 不在接口层吞异常，具体异常由实现类与上层统一处理。
 */
public interface AcademicAgentStrategy {

    /**
     * 返回当前策略对应的角色类型标识，例如 reviewer、editor。
     */
    String getRoleType();

    /**
     * 关键步骤：
     * 1) 根据角色注入特定 system prompt；
     * 2) 按会话键规范构建并绑定 ChatMemory 会话 id；
     * 3) 执行模型调用并返回结果文本。
     * 异常处理：模型调用异常保持上抛，由统一异常机制处理。
     */
    Flux<String> process(String chatId, String prompt);
}

