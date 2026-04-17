package com.science.ai.service.archive;

import com.science.ai.entity.vo.MessageVo;

import java.util.List;

/**
 * 职责：提供聊天历史明细查询能力，统一从 MySQL 完整归档读取消息数据。
 * 输入：业务类型 type 与会话标识 chatId。
 * 输出：按时间顺序排列的消息视图列表（MessageVo）。
 * 边界条件：
 * 1) 查询不到历史时返回空列表，不抛业务异常；
 * 2) 当前 userId 先统一走默认值（anonymous），为后续登录态透传预留扩展点。
 */
public interface ChatArchiveQueryService {

    /**
     * 关键步骤：
     * 1) 对 type/chatId 做标准化，避免空白字符导致查询 miss；
     * 2) 使用 MySQL 归档表查询完整历史，避免 Redis 窗口裁剪丢失旧消息；
     * 3) 将归档实体映射为前端既有 MessageVo，保持接口返回兼容。
     * 异常处理：参数非法时抛 IllegalArgumentException；数据库异常沿用全局异常链路。
     */
    List<MessageVo> queryChatHistory(String type, String chatId);
}

