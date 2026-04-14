package com.science.ai.repository;

import java.util.List;

public interface ChatHistoryRepo {

    /**
     * 保存会话的记录 即id信息
     * @param type  业务类型 如chat service pdf
     * @param chatId    会话id
     */
    void save(String type, String chatId);

    /**
     * 根据业务类型 查询全部会话 不分用户目前
     * @param type
     * @return  会话 id 的列表
     */
    List<String> queryChatIds(String type);
}
