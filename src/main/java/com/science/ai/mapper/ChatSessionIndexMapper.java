package com.science.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.science.ai.entity.po.ChatSessionIndex;

/**
 * 职责：chat_session_index 的 MyBatis-Plus 基础 Mapper。
 * 输入输出：提供基础 CRUD 能力，复杂 upsert 在仓储层统一实现。
 * 边界条件：不承载会话索引幂等策略，避免与业务层耦合。
 */
public interface ChatSessionIndexMapper extends BaseMapper<ChatSessionIndex> {
}

