package com.science.ai.repository.other;

import org.springframework.core.io.Resource;

public interface FileRepo {

    /**
     * 保存文件 并记录chatId 与 文件的映射关系
     * @param chatId 会话id
     * @param resource  文件
     * @return  成功返回true
     */
    boolean save(String chatId, Resource resource);

    /**
     * 根据chatId获取对应上传的文件
     * @param chatId    会话id
     * @return  找到的文件
     *  这个Resource资源 可以代表文件资源 获取 网络资源
     */
    Resource getFile(String chatId);
}
