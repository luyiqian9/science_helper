package com.science.ai.service.archive.impl;

import com.science.ai.config.chat.ChatArchiveProperties;
import com.science.ai.repository.mysql.ChatArchiveRepository;
import com.science.ai.service.archive.ChatArchiveFacadeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 职责：聊天归档门面实现，集中封装归档开关、默认 userId 与 fail-safe 策略。
 * 输入：消息归档参数或会话索引参数。
 * 输出：无返回值，主链路可持续运行。
 * 边界条件：
 * 1) mysql-enabled=false 时所有归档请求都快速返回；
 * 2) payload 为空时跳过消息归档，避免无意义数据库操作。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatArchiveFacadeServiceImpl implements ChatArchiveFacadeService {

    private final ChatArchiveRepository chatArchiveRepository;
    private final ChatArchiveProperties chatArchiveProperties;

    /**
     * 关键步骤：
     * 1) 判断归档开关；
     * 2) 统一使用 defaultUserId 作为当前阶段用户标识；
     * 3) 调用底层仓储执行 MySQL 消息归档。
     * 异常处理：采用 fail-safe 策略，异常只记录 error，避免影响 Redis 记忆主链路。
     */
    @Override
    public void archiveMessagesFailSafe(String conversationKey, List<ChatArchiveRepository.ArchiveMessagePayload> payloads) {
        if (!chatArchiveProperties.isMysqlEnabled() || payloads == null || payloads.isEmpty()) {
            return;
        }

        try {
            chatArchiveRepository.archiveMessages(conversationKey, payloads, chatArchiveProperties.getDefaultUserId());
        } catch (Exception ex) {
            log.error("Archive messages to MySQL failed but redis path remains successful. conversationKey={}",
                    conversationKey,
                    ex);
        }
    }

    /**
     * 关键步骤：
     * 1) 判断归档开关；
     * 2) 统一使用 defaultUserId；
     * 3) 调用底层仓储执行会话索引 upsert。
     * 异常处理：采用 fail-safe 策略，异常只记录 error，保持会话主链路行为不变。
     */
    @Override
    public void saveSessionIndexFailSafe(String bizType, String chatId) {
        if (!chatArchiveProperties.isMysqlEnabled()) {
            return;
        }

        try {
            chatArchiveRepository.saveSessionIfAbsentOrUpdate(bizType, chatId, chatArchiveProperties.getDefaultUserId());
        } catch (Exception ex) {
            log.error("Archive session index to MySQL failed but redis path remains successful. type={}, chatId={}",
                    bizType,
                    chatId,
                    ex);
        }
    }
}

