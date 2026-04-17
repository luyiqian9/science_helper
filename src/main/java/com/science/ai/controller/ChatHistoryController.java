package com.science.ai.controller;

import com.science.ai.constants.RoleTypeConstants;
import com.science.ai.entity.vo.MessageVo;
import com.science.ai.repository.redis.ChatHistoryRepo;
import com.science.ai.service.archive.ChatArchiveQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 职责：提供会话历史查询接口（会话列表 + 消息明细）。
 * 输入：业务类型 type 与 chatId。
 * 输出：chatId 列表或消息视图列表。
 * 边界条件：
 * 1) 历史 id 列表仍由 ChatHistoryRepo(Redis) 提供，保证列表查询性能；
 * 2) 消息明细统一改为从 MySQL 完整归档读取，避免 Redis 窗口裁剪导致历史丢失。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/history")
public class ChatHistoryController {

    private final ChatHistoryRepo chatHistoryRepo;
    private final ChatArchiveQueryService chatArchiveQueryService;

    /**
     * 关键步骤：按业务类型查询历史 chatId 列表。
     * 异常处理：仓库异常沿用现有全局异常链路。
     */
    @GetMapping("/{type}")
    public List<String> getChatIds(@PathVariable("type") String type) {
        // TODO 前端未修改type 默认只会为 chat 所以在这里修改为 editor 测试 不然无法查询到聊天记录
        type = RoleTypeConstants.EDITOR;

        return chatHistoryRepo.queryChatIds(type);
    }

    /**
     * 关键步骤：
     * 1) 保留现有 type 兼容逻辑；
     * 2) 委托归档查询服务读取 MySQL 全量历史；
     * 3) 返回既有 MessageVo 结构，保持前端协议不变。
     * 异常处理：查询异常沿用全局异常链路。
     */
    @GetMapping("/{type}/{chatId}")
    public List<MessageVo> queryChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        // TODO 前端未修改type 默认只会为 chat 所以在这里修改为 editor 测试 不然无法查询到聊天记录
        type = RoleTypeConstants.EDITOR;

        return chatArchiveQueryService.queryChatHistory(type, chatId);
    }
}
