package com.science.ai.service.archive.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.science.ai.config.chat.ChatArchiveProperties;
import com.science.ai.entity.po.ChatMessageArchive;
import com.science.ai.entity.vo.MessageVo;
import com.science.ai.mapper.ChatMessageArchiveMapper;
import com.science.ai.service.archive.ChatArchiveQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 职责：基于 MyBatis-Plus 从 MySQL 归档表读取会话全量历史，并转换为 MessageVo。
 * 输入：业务类型 type、会话 ID chatId。
 * 输出：按归档顺序（id 升序）返回消息视图列表。
 * 边界条件：
 * 1) 仅查询未软删记录（isDeleted=0），避免返回逻辑删除数据；
 * 2) 当前 userId 由配置 default-user-id 提供，兼容未接入登录态场景；
 * 3) mysql-enabled=false 时返回空列表，保持主链路行为稳定。
 */
@Service
@RequiredArgsConstructor
public class ChatArchiveQueryServiceImpl extends ServiceImpl<ChatMessageArchiveMapper, ChatMessageArchive> implements ChatArchiveQueryService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ChatMessageArchiveMapper chatMessageArchiveMapper;
    private final ChatArchiveProperties chatArchiveProperties;

    /**
     * 关键步骤：
     * 1) 校验并标准化入参，避免因为前后空格导致查不到历史；
     * 2) 用 MP 条件查询 bizType/chatId/userId 下的完整消息明细；
     * 3) 按 id 升序返回，确保与 Redis list rpush 的时间顺序一致；
     * 4) 将 PO 映射到 MessageVo，保持接口层返回结构不变。
     * 异常处理：参数为空抛 IllegalArgumentException；数据库异常由 Spring 统一处理。
     */
    @Override
    public List<MessageVo> queryChatHistory(String type, String chatId) {
        if (!chatArchiveProperties.isMysqlEnabled()) {
            return Collections.emptyList();
        }
        if (!StringUtils.hasText(type) || !StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("type and chatId must not be blank");
        }

        String normalizedType = type.trim();
        String normalizedChatId = chatId.trim();
        String normalizedUserId = normalizeUserId(chatArchiveProperties.getDefaultUserId());

        List<ChatMessageArchive> archives = query().eq("user_id", normalizedUserId)
                .eq("biz_type", normalizedType)
                .eq("chat_id", normalizedChatId)
                .eq("is_deleted", 0)
                .orderByAsc("id").list();

        if (archives == null || archives.isEmpty()) {
            return Collections.emptyList();
        }

        return archives.stream().map(this::toMessageVo).collect(Collectors.toList());
    }

    /**
     * 关键步骤：
     * 1) 将 USER/ASSISTANT 映射为前端既有角色标识；
     * 2) 其他角色（如 SYSTEM/TOOL）先返回空 role，保持与历史 MessageVo 构造逻辑一致；
     * 3) textContent 为空时返回空串，避免前端出现 null 判空分支。
     * 异常处理：本方法不抛业务异常，做空值兜底。
     */
    private MessageVo toMessageVo(ChatMessageArchive archive) {
        MessageVo vo = new MessageVo();
        vo.setRole(toRole(archive.getMessageType()));
        vo.setContent(archive.getTextContent() == null ? "" : archive.getTextContent());
        return vo;
    }

    private String toRole(String messageType) {
        if ("USER".equals(messageType)) {
            return ROLE_USER;
        }
        if ("ASSISTANT".equals(messageType)) {
            return ROLE_ASSISTANT;
        }
        return "";
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId.trim() : "anonymous";
    }
}
