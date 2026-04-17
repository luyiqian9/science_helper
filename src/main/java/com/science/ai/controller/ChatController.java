package com.science.ai.controller;
import com.science.ai.constants.RoleTypeConstants;
import com.science.ai.service.AcademicAgentService;
import com.science.ai.service.chat.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 职责：处理通用聊天请求（纯文本/多模态），并把会话上下文写入 ChatMemory。
 * 输入：type、prompt、chatId、可选文件列表。
 * 输出：按流式方式返回模型生成内容。
 * 边界条件：
 * 1) type=chat 时复用原通用聊天链路（支持纯文本/多模态）；
 * 2) type!=chat 时按角色路由到 AcademicAgentService 的策略实现；
 * 3) 会话维度统一使用 chatId，避免引入 user 概念导致上下文口径不一致。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    /**
     * 通用聊天业务类型常量。
     * 说明：conversationKey 统一使用 {type}:{chatId}，这里固定 type=chat。
     */
    private static final String TYPE = "chat";

    private final ChatService chatService;
    private final AcademicAgentService academicAgentService;

    /**
     * 异常处理：参数缺失或模型调用异常沿用现有全局异常处理。
     */
    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(
            @RequestParam(value = "type", required = false, defaultValue = TYPE) String type,
            @RequestParam("prompt") String prompt,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        // 关键步骤1：先做最小参数规范化，避免前后空白导致 roleType 路由不命中。
        String normalizedType = type == null ? TYPE : type.trim();

        // TODO 前端未修改type 默认只会为 chat 所以在这里修改为 editor 测试
        normalizedType = RoleTypeConstants.EDITOR;

        // 关键步骤2：type=chat 走原聊天链路；其他 type 直接交由策略上下文服务动态路由。
        if (TYPE.equals(normalizedType)) {
            return chatService.chat(TYPE, prompt, chatId, files);
        }
        return academicAgentService.processAcademicText(chatId, prompt, normalizedType);
    }

}
