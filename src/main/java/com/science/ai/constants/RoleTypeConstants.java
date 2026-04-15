package com.science.ai.constants;

/**
 * 职责：集中维护 Academic Agent 的角色类型常量，避免策略实现中散落硬编码字符串。
 * 输入输出：仅提供静态常量，不接收运行时输入，也不产生动态输出。
 * 边界条件：
 * 1) 本类只承载角色标识，不承载路由或业务逻辑；
 * 2) 禁止实例化，防止被误用为状态对象。
 */
public final class RoleTypeConstants {

    /** 审稿人角色标识。 */
    public static final String REVIEWER = "reviewer";

    /** 顶会编辑角色标识。 */
    public static final String EDITOR = "editor";

    private RoleTypeConstants() {
        // 工具类不允许实例化。
    }
}

