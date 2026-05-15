package com.smartquery.prompt;

/**
 * 提示词注入条件
 */
@FunctionalInterface
public interface PromptCondition {
    boolean test(PromptContext ctx);
}
