package com.science.ai.controller;

import com.science.ai.constants.RoleTypeConstants;
import com.science.ai.entity.vo.MessageVo;
import com.science.ai.repository.redis.ChatHistoryRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 职责：提供会话历史查询接口（会话列表 + 消息明细）。
 * 输入：业务类型 type 与 chatId。
 * 输出：chatId 列表或消息视图列表。
 * 边界条件：
 * 1) 历史 id 列表仍由 ChatHistoryRepo 提供（本批不改造）；
 * 2) 消息读取统一使用 conversationKey=type:chatId。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/history")
public class ChatHistoryController {

    private final ChatHistoryRepo chatHistoryRepo;
    private final ChatMemory chatMemory;

    /**
     * 关键步骤：按业务类型查询历史 chatId 列表。
     * 异常处理：仓库异常沿用现有全局异常链路。
     */
    @GetMapping("/{type}")
    public List<String> getChatIds(@PathVariable("type") String type) {
        // TODO 前端未修改type 默认只会为 chat 所以在这里修改为 editor 测试 不然无法查询到聊天记录
        type = RoleTypeConstants.REVIEWER;

        return chatHistoryRepo.queryChatIds(type);
    }

    /**
     * 关键步骤：
     * 1) 组装 conversationKey=type:chatId；
     * 2) 读取 ChatMemory 消息并映射为 MessageVo。
     * 异常处理：无消息时返回空列表。
     */
    @GetMapping("/{type}/{chatId}")
    public List<MessageVo> queryChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        // TODO 前端未修改type 默认只会为 chat 所以在这里修改为 editor 测试 不然无法查询到聊天记录
        type = RoleTypeConstants.REVIEWER;

        String conversationKey = buildConversationKey(type, chatId);
        List<Message> messages = chatMemory.get(conversationKey);
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<MessageVo> res = messages.stream().map(MessageVo::new).collect(Collectors.toList());
        return res;
    }

    /**
     * 关键步骤：
     * 1) 统一组装 conversationKey={type}:{chatId}；
     * 2) 对 type/chatId 做 trim，避免历史查询时因空白字符命中不到已存消息；
     * 3) 与各业务 Controller 的写入规则保持一致，保障读写兼容。
     * 异常处理：本方法不吞异常，参数异常由调用方处理。
     */
    private String buildConversationKey(String type, String chatId) {
        // trim：将外部路径参数标准化，避免 "chat " 与 "chat" 产生不同 key。
        String normalizedType = type == null ? "" : type.trim();
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        return normalizedType + ":" + normalizedChatId;
    }

}
