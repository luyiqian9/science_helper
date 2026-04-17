package com.science.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.science.ai.entity.po.ChatMessageArchive;

/**
 * 职责：chat_message_archive 的 MyBatis-Plus 基础 Mapper。
 * 输入输出：提供基础 CRUD 能力，复杂 insert 在仓储层统一实现。
 * 边界条件：仅承载数据库映射职责，不包含业务去重策略。
 */
public interface ChatMessageArchiveMapper extends BaseMapper<ChatMessageArchive> {
}

