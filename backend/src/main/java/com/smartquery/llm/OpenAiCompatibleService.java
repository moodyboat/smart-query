package com.smartquery.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.LlmConfigEntity;
import com.smartquery.mapper.LlmConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议实现 — 智谱/OpenAI/DeepSeek 共用
 *
 * <p>复用智慧监督 DirectLlmService:
 * <ul>
 *   <li>chatWithTools() 动态模型配置</li>
 *   <li>指数退避重试</li>
 *   <li>reasoning_content 支持 (DeepSeek)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiCompatibleService implements LlmService {

    private final LlmConfigMapper llmConfigMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_DELAY_MS = 1000;

    @Override
    public String chat(String model, List<Map<String, String>> messages) {
        List<LlmChunk> chunks = chatWithTools(model,
            messages.stream().map(m -> (Map<String, Object>) (Map<String, ?>) m).toList(),
            List.of());
        StringBuilder sb = new StringBuilder();
        for (LlmChunk chunk : chunks) {
            if (chunk.isText()) {
                sb.append(chunk.text());
            }
        }
        return sb.toString();
    }

    @Override
    public List<LlmChunk> chatWithTools(String model, List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return chatWithToolsStreaming(model, messages, tools, null);
    }

    @Override
    public List<LlmChunk> chatWithToolsStreaming(String model, List<Map<String, Object>> messages,
                                                  List<Map<String, Object>> tools, Consumer<String> textTokenConsumer) {
        LlmConfigEntity config = resolveConfig(model);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", config.getApiKey() != null && !config.getApiKey().isEmpty() ? model : config.getModelCode());
                body.put("messages", messages);
                body.put("max_tokens", config.getMaxTokens());
                body.put("temperature", config.getTemperature());
                body.put("stream", true);
                if (!tools.isEmpty()) {
                    body.put("tools", tools);
                    body.put("tool_choice", "auto");
                }

                String jsonBody = objectMapper.writeValueAsString(body);
                log.debug("[LLM] model={}, messages={}, tools={}, stream=true, attempt={}", model, messages.size(), tools.size(), attempt);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(180))
                    .build();

                HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    if (attempt < MAX_RETRIES) {
                        long delay = INITIAL_DELAY_MS * (1L << attempt);
                        log.warn("[LLM] retry after {}ms, status={}", delay, response.statusCode());
                        Thread.sleep(delay);
                        continue;
                    }
                    throw new RuntimeException("LLM API error after " + MAX_RETRIES + " retries: " + response.statusCode());
                }

                if (response.statusCode() != 200) {
                    String errorBody = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    throw new RuntimeException("LLM API error: " + response.statusCode() + " " + errorBody);
                }

                return parseSseStream(response.body(), textTokenConsumer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM call interrupted", e);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("LLM call failed after " + MAX_RETRIES + " retries", e);
                }
                log.warn("[LLM] attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * 解析 SSE 流式响应，实时转发文本 token
     */
    private List<LlmChunk> parseSseStream(java.io.InputStream inputStream, Consumer<String> textTokenConsumer) throws Exception {
        List<LlmChunk> chunks = new ArrayList<>();
        StringBuilder contentBuilder = new StringBuilder();
        // tool_calls 可能分多个 delta 到达，需要组装
        Map<Integer, String> toolCallIdMap = new LinkedHashMap<>();
        Map<Integer, String> toolCallNameMap = new LinkedHashMap<>();
        Map<Integer, StringBuilder> toolCallArgsMap = new LinkedHashMap<>();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            int inputTokens = 0, outputTokens = 0;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;

                    JsonNode event;
                    try {
                        event = objectMapper.readTree(data);
                    } catch (Exception e) {
                        continue;
                    }

                    JsonNode choices = event.path("choices");
                    if (choices.isEmpty()) continue;
                    JsonNode choice = choices.get(0);
                    JsonNode delta = choice.path("delta");

                    // 文本内容
                    String content = delta.path("content").asText(null);
                    if (content != null) {
                        contentBuilder.append(content);
                        if (textTokenConsumer != null) {
                            textTokenConsumer.accept(content);
                        }
                    }

                    // tool_calls 增量
                    JsonNode toolCalls = delta.path("tool_calls");
                    if (toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            int index = tc.path("index").asInt();
                            if (tc.has("id")) {
                                toolCallIdMap.put(index, tc.path("id").asText());
                                toolCallNameMap.put(index, tc.path("function").path("name").asText());
                                toolCallArgsMap.computeIfAbsent(index, k -> new StringBuilder());
                            }
                            JsonNode argsDelta = tc.path("function").path("arguments");
                            if (!argsDelta.isMissingNode() && argsDelta.isTextual()) {
                                toolCallArgsMap.computeIfAbsent(index, k -> new StringBuilder())
                                    .append(argsDelta.asText());
                            }
                        }
                    }

                    // usage (某些 API 在最后一个 chunk 中返回)
                    JsonNode usage = event.path("usage");
                    if (!usage.isMissingNode()) {
                        inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                        outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                    }
                }
            }

            // 组装最终结果
            String fullContent = contentBuilder.toString();
            if (!fullContent.isEmpty()) {
                chunks.add(LlmChunk.text(fullContent));
            }

            for (Map.Entry<Integer, String> entry : toolCallIdMap.entrySet()) {
                int idx = entry.getKey();
                String tcId = entry.getValue();
                String tcName = toolCallNameMap.getOrDefault(idx, "");
                String tcArgs = toolCallArgsMap.getOrDefault(idx, new StringBuilder()).toString();
                chunks.add(LlmChunk.toolCall(tcId, tcName, tcArgs));
            }

            chunks.add(LlmChunk.done("stop", inputTokens, outputTokens));
        }

        log.debug("[LLM] SSE parsed: text={}chars, toolCalls={}", contentBuilder.length(), toolCallIdMap.size());
        return chunks;
    }

    private LlmConfigEntity resolveConfig(String model) {
        LlmConfigEntity config = llmConfigMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmConfigEntity>()
                .eq(LlmConfigEntity::getModelCode, model)
                .eq(LlmConfigEntity::getStatus, 1)
                .last("LIMIT 1")
        );
        if (config != null) {
            return config;
        }
        return getDefaultConfig(model);
    }

    private LlmConfigEntity getDefaultConfig(String model) {
        LlmConfigEntity config = new LlmConfigEntity();
        config.setModelCode(model);
        String apiUrl = System.getenv("LLM_API_URL");
        String apiKey = System.getenv("LLM_API_KEY");
        config.setApiUrl(apiUrl != null ? apiUrl : "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions");
        config.setApiKey(apiKey != null ? apiKey : "");
        config.setMaxTokens(4096);
        config.setTemperature(java.math.BigDecimal.valueOf(0.1));
        return config;
    }

    @Override
    public boolean isAvailable(String model) {
        try {
            resolveConfig(model);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getAvailableModels() {
        List<LlmConfigEntity> configs = llmConfigMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmConfigEntity>()
                .eq(LlmConfigEntity::getStatus, 1)
        );
        return configs.stream().map(LlmConfigEntity::getModelCode).toList();
    }
}
