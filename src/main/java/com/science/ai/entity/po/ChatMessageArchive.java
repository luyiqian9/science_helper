package com.science.ai.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 职责：映射 MySQL 表 chat_message_archive，承载聊天消息完整归档。
 * 输入：归档写入链路写入的文本/多模态/tool 等结构化字段。
 * 输出：供 MyBatis-Plus 查询历史明细并转换为前端 MessageVo。
 * 边界条件：
 * 1) 当前业务主链路主要使用 textContent/messageType，其他扩展字段按默认值保留；
 * 2) 同一条消息通过 msgFingerprint 唯一约束去重，避免窗口重放重复入库。
 */
@Data
@TableName("chat_message_archive")
public class ChatMessageArchive implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键（全局顺序）。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型（type），例如 chat/reviewer/editor。 */
    private String bizType;

    /** 会话 ID（chatId）。 */
    private String chatId;

    /** 用户 ID，当前默认 anonymous。 */
    private String userId;

    /** 生成列：type:chatId，用于便捷检索。 */
    private String conversationKey;

    /** 消息类型：USER/ASSISTANT/SYSTEM/TOOL。 */
    private String messageType;

    /** 消息格式：TEXT/MULTIMODAL/TOOL_CALL/TOOL_RESULT。 */
    private String messageFormat;

    /** 纯文本内容。 */
    private String textContent;

    /** 多模态结构化内容 JSON。 */
    private String contentJson;

    /** 原始 createdAt 字符串。 */
    private String createdAtRaw;

    /** 解析后的消息时间。 */
    private LocalDateTime createdAtTs;

    /** 原始 metadata JSON。 */
    private String metadataJson;

    /** 工具调用 ID。 */
    private String toolCallId;

    /** 工具名。 */
    private String toolName;

    /** 工具参数 JSON。 */
    private String toolArgsJson;

    /** 工具返回 JSON。 */
    private String toolResultJson;

    /** 预留：输入 tokens。 */
    private Integer promptTokens;

    /** 预留：输出 tokens。 */
    private Integer completionTokens;

    /** 预留：总 tokens。 */
    private Integer totalTokens;

    /** 预留：耗时毫秒。 */
    private Integer latencyMs;

    /** 去重指纹：sha256(message_type|text|created_at_raw)。 */
    private String msgFingerprint;

    /** 来源：CHAT/RAG/TOOL/SYSTEM。 */
    private String source;

    /** 软删标记：0=有效，1=删除。 */
    private Integer isDeleted;

    /** 预留扩展 JSON。 */
    private String extJson;

    /** 归档落库时间。 */
    private LocalDateTime savedAt;
}
