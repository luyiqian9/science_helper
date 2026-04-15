package com.science.ai.service;

import com.science.ai.repository.ChatHistoryRepo;
import com.science.ai.strategy.AcademicAgentStrategy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 职责：作为学术角色 Agent 的上下文调度服务，负责根据 roleType 将请求路由到对应策略。
 * 输入：chatId、prompt、roleType。
 * 输出：目标角色策略处理后的文本结果。
 * 边界条件：
 * 1) roleType 必须命中已注册策略，否则抛出明确异常；
 * 2) 路由过程只做策略选择，不侵入具体大模型调用细节。
 */
@Service
public class AcademicAgentService {

    private final Map<String, AcademicAgentStrategy> strategyMap;
    private final ChatHistoryRepo chatHistoryRepo;

    /**
     * 关键步骤：
     * 1) 通过 Spring IOC 注入全部策略实现；
     * 2) 按 getRoleType() 构建 roleType -> strategy 的路由表。
     * 异常处理：若存在重复 roleType，会在应用启动阶段抛异常，尽早暴露配置问题。
     */
    public AcademicAgentService(List<AcademicAgentStrategy> strategies, ChatHistoryRepo chatHistoryRepo) {
        this.chatHistoryRepo = chatHistoryRepo;
        // 策略模式: 基于 Spring IOC 消除 if-else，实现角色动态路由。
        this.strategyMap = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AcademicAgentStrategy::getRoleType,
                        Function.identity()
                ));
    }

    /**
     * 关键步骤：
     * 1) 根据 roleType 从 Map 选择目标策略；
     * 2) 先写入历史 chatId，确保历史列表能反映真实调用行为；
     * 3) 调用策略的 process 执行大模型逻辑。
     * 异常处理：roleType 不存在时抛 IllegalArgumentException，避免静默降级。
     */
    public Flux<String> processAcademicText(String chatId, String prompt, String roleType) {
        AcademicAgentStrategy strategy = Optional.ofNullable(strategyMap.get(roleType))
                .orElseThrow(() -> new IllegalArgumentException("Unsupported roleType: " + roleType));

        // 保持既有仓储契约：save(type, chatId)，这里 type 使用 roleType（reviewer/editor）实现角色会话历史隔离。
        chatHistoryRepo.save(roleType, chatId);

        return strategy.process(chatId, prompt);
    }
}

