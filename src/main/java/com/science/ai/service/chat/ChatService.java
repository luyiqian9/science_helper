package com.science.ai.service.chat;

import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 职责：承接聊天请求的业务编排，向上提供统一 chat 入口，向下协调历史记录与模型调用。
 * 输入：
 * 1) type：业务类型标识，用于隔离不同业务会话空间；
 * 2) prompt：用户输入文本；
 * 3) chatId：会话 id，由接口层传入；
 * 4) files：可选附件列表，空/null 表示纯文本链路。
 * 输出：按流式返回模型内容（Flux<String>），保持与控制器现有返回契约一致。
 * 边界条件：
 * 1) conversationKey 规则固定为 {type}:{chatId}（内部会做 trim 规范化）；
 * 2) files 为空或空集合时走纯文本路径，非空时走多模态路径；
 * 3) 不改变底层异常传播策略，模型调用或仓储异常由上层统一处理。
 */
public interface ChatService {

    /**
     * 关键步骤：
     * 1) 组装 conversationKey 作为 ChatMemory 上下文标识；
     * 2) 保存会话历史（保留 save(type, chatId) 调用契约）；
     * 3) 根据 files 是否为空分流 text/multimodal。
     * 异常处理：保持现有异常语义，不在 service 内吞异常。
     */
    Flux<String> chat(String type, String prompt, String chatId, List<MultipartFile> files);
}

