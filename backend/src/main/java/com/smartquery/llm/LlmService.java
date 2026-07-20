package com.smartquery.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 统一服务接口
 */
public interface LlmService {

    String chat(String model, List<Map<String, String>> messages);

    List<LlmChunk> chatWithTools(String model, List<Map<String, Object>> messages, List<Map<String, Object>> tools);

    /**
     * 流式对话 (带工具 + 文本 token 实时回调)
     * textTokenConsumer 在每个文本 delta 到达时立即调用，用于实时推送 LLM 思考过程
     */
    default List<LlmChunk> chatWithToolsStreaming(String model, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, Consumer<String> textTokenConsumer) {
        return chatWithTools(model, messages, tools);
    }

    boolean isAvailable(String model);
}
