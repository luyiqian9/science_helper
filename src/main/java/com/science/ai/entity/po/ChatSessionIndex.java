package com.science.ai.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 职责：映射 MySQL 表 chat_session_index，维护会话索引信息。
 * 输入：业务类型、用户标识、会话标识及时间戳。
 * 输出：用于会话索引 upsert 的实体对象。
 * 边界条件：同一 (bizType,userId,chatId) 需幂等，重复写入只更新 lastSavedAt。
 */
@Data
@TableName("chat_session_index")
public class ChatSessionIndex implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String bizType;

    private String userId;

    private String chatId;

    private LocalDateTime firstSavedAt;

    private LocalDateTime lastSavedAt;
}

