package com.smartquery.prompt;

/**
 * 提示词优先级 — 直译 Claude Code prompt priority 层级
 *
 * <p>优先级从高到低:
 * <ol>
 *   <li>OVERRIDE — 最高优先级，覆盖所有其他段（如安全覆盖）</li>
 *   <li>COORDINATOR — 编排层指令（如多 Agent 协调）</li>
 *   <li>AGENT — Agent 行为指令（如 ReAct 策略）</li>
 *   <li>CUSTOM — 用户/场景自定义段</li>
 *   <li>DEFAULT — 默认系统段（角色、能力、规则）</li>
 *   <li>APPEND — 追加段（如 schema 上下文，可被截断）</li>
 * </ol>
 *
 * <p>Token 预算控制: 当总 prompt 超预算时，从 APPEND 开始截断
 */
public enum PromptPriority {
    OVERRIDE(100),
    COORDINATOR(80),
    AGENT(60),
    CUSTOM(40),
    DEFAULT(20),
    APPEND(10);

    private final int weight;

    PromptPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
