package com.science.ai.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 职责：映射 MySQL 表 chat_message_archive，用于保存完整消息归档。
 * 输入：由归档仓储层填充的消息字段与扩展字段。
 * 输出：提供给 MyBatis-Plus 的持久化实体。
 * 边界条件：允许 tool* 与 contentJson 等扩展字段为空，保证当前纯文本链路不报错。
 */
@Data
@TableName("chat_message_archive")
public class ChatMessageArchive implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String bizType;

    private String chatId;

    private String userId;

    private String messageType;

    private String messageFormat;

    private String textContent;

    private String contentJson;

    private String createdAtRaw;

    private String metadataJson;

    private String toolName;

    private String toolCallId;

    private String toolArgsJson;

    private String msgFingerprint;

    private LocalDateTime savedAt;
}

