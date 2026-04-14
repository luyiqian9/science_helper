package com.science.ai.repository;

import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 职责：使用 Redis 维护各业务类型(type)下的会话 id 列表，替代内存实现。
 * 输入：业务类型 type、会话 id(chatId)。
 * 输出：保存会话 id（幂等）或按最近优先返回会话 id 列表。
 * 边界条件：
 * 1) type/chatId 为空白时直接忽略，避免写入脏 key；
 * 2) Redis 无数据时返回空列表，不返回 null；
 * 3) 使用 ZSET 保证“去重+排序”同时成立，避免 List 结构重复扫描。
 * 为什么用 ZSET：
 * - member 天然唯一，可直接表达 chatId 去重语义；
 * - score 可表达时间顺序，支持最近优先查询；
 * - 相比 List，避免每次 save 时全量 contains 查重。
 */
@Component
@Primary
public class RedisChatHistoryRepo implements ChatHistoryRepo {

    private static final String KEY_PREFIX = "ai:chat:ids:";

    private final StringRedisTemplate redisTemplate;

    public RedisChatHistoryRepo(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 关键步骤：
     * 1) 组装 zset key = ai:chat:ids:{type}；
     * 2) 先查 score 判断 chatId 是否已存在；
     * 3) 仅首次出现时写入 score=首次保存时间戳。
     * 异常处理：Redis 异常交由上层统一处理，不吞异常。
     * 为什么 score 定义为首次保存时间戳：
     * - 满足“save 幂等去重”，重复 save 不改 score；
     * - 能稳定记录该会话首次出现时间；
     * - 配合 reverseRange 可按时间倒序读取，满足“最近优先”。
     */
    @Override
    public void save(String type, String chatId) {
        if (!StringUtils.hasText(type) || !StringUtils.hasText(chatId)) {
            return;
        }

        String normalizedType = type.trim();
        String normalizedChatId = chatId.trim();
        String key = buildKey(normalizedType);

        double firstSavedAt = Instant.now().toEpochMilli();

        // 对应 Redis: ZADD key NX score member
        // 含义：仅当 member 不存在时写入；存在则不更新 score（保留首次时间）
        redisTemplate.opsForZSet().addIfAbsent(key, normalizedChatId, firstSavedAt);
    }

    /**
     * 关键步骤：
     * 1) 根据 type 计算 zset key；
     * 2) 使用 ZSET 倒序读取（score 从大到小）；
     * 3) 返回最近优先的 chatId 列表。
     * 异常处理：type 为空或 Redis 无数据时返回空列表。
     */
    @Override
    public List<String> queryChatIds(String type) {
        if (!StringUtils.hasText(type)) {
            return Collections.emptyList();
        }

        String key = buildKey(type.trim());
        // 对应 Redis: ZREVRANGE key 0 -1
        // 含义：返回 score 从大到小排序的 member 列表
        Set<String> values = redisTemplate.opsForZSet().reverseRange(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(values);
    }

    private String buildKey(String type) {
        return KEY_PREFIX + type;
    }
}

