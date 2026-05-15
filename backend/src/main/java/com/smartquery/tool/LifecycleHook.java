package com.smartquery.tool;

import java.util.List;
import java.util.Map;

/**
 * 生命周期 Hook — 扩展 ToolHook，新增会话级和用户输入级钩子
 *
 * <p>对标 Claude Code 的 4 种 Hook:
 * <ul>
 *   <li>beforeToolCall / afterToolCall — 工具执行前后</li>
 *   <li>onSessionStart — 会话开始时注入上下文</li>
 *   <li>onUserPrompt — 用户输入提交时预处理</li>
 * </ul>
 */
public interface LifecycleHook extends ToolHook {

    /**
     * 会话开始时调用 — 注入初始上下文
     *
     * @param context 上下文 (conversationId, dataSourceId 等)
     * @return 要注入到系统提示词的附加段落，null 表示不注入
     */
    default String onSessionStart(Map<String, Object> context) {
        return null;
    }

    /**
     * 用户输入提交时调用 — 预处理用户消息
     *
     * @param userMessage 用户原始消息
     * @param context     上下文
     * @return 处理后的消息，null 表示不修改
     */
    default String onUserPrompt(String userMessage, Map<String, Object> context) {
        return null;
    }
}
