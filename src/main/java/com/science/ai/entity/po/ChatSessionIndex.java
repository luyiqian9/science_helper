package com.science.ai.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 职责：映射 MySQL 表 chat_session_index，持久化会话索引镜像数据。
 * 输入：业务类型、会话 ID、用户 ID 与时间戳统计字段。
 * 输出：供 MyBatis-Plus/JDBC 做会话索引查询与幂等更新。
 * 边界条件：
 * 1) firstSavedAt/lastSavedAt 使用毫秒时间戳（BIGINT），与 Redis ZSET score 对齐；
 * 2) (bizType,userId,chatId) 唯一约束保证索引幂等。
 */
@Data
@TableName("chat_session_index")
public class ChatSessionIndex implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型（type）。 */
    private String bizType;

    /** 会话 ID（chatId）。 */
    private String chatId;

    /** 用户 ID，当前默认 anonymous。 */
    private String userId;

    /** 首次保存时间戳（毫秒）。 */
    private Long firstSavedAt;

    /** 最近保存时间戳（毫秒）。 */
    private Long lastSavedAt;

    /** 消息计数（预留统计）。 */
    private Long messageCount;

    /** 会话状态：1=有效，0=归档/禁用。 */
    private Integer status;

    /** 预留扩展 JSON。 */
    private String extJson;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 记录更新时间。 */
    private LocalDateTime updatedAt;
}
