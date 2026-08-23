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

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.http-timeout-seconds:180}")
    private int httpTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.max-retries:10}")
    private int maxRetries;

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.retry-delay-ms:2000}")
    private long retryDelayMs;

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.max-retry-delay-ms:10000}")
    private long maxRetryDelayMs;

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.connect-timeout-seconds:30}")
    private int connectTimeoutSeconds;

    private HttpClient httpClient;

    @jakarta.annotation.PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .build();
    }

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

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 验证 API key 配置（只在第一次尝试时检查，避免重复日志）
                if (attempt == 0 && (config.getApiKey() == null || config.getApiKey().isBlank())) {
                    String errorMsg = String.format("LLM API key 未配置，请检查配置文件或环境变量 (model=%s)", model);
                    log.error("[LLM] {}", errorMsg);
                    throw new RuntimeException(errorMsg);
                }

                // 验证 messages 格式，避免非法格式导致 API 调用失败
                if (!validateMessages(messages)) {
                    throw new RuntimeException("LLM messages 格式非法（role/content 缺失或超长），拒绝调用");
                }

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
                    .timeout(Duration.ofSeconds(httpTimeoutSeconds))
                    .build();

                HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    if (attempt < maxRetries) {
                        long delay = computeRetryDelayMs(attempt);
                        log.warn("[LLM] retry after {}ms (attempt {}/{}, max-cap {}ms), status={}", delay, attempt + 1, maxRetries, maxRetryDelayMs, response.statusCode());
                        Thread.sleep(delay);
                        continue;
                    }
                    throw new RuntimeException("LLM API error after " + maxRetries + " retries: status=" + response.statusCode());
                }

                if (response.statusCode() != 200) {
                    String errorBody = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    // 4xx 错误不重试，直接失败（参数非法、鉴权失败、模型不存在等）
                    if (response.statusCode() >= 400 && response.statusCode() < 500) {
                        log.error("[LLM] client error {} (not retryable): {}", response.statusCode(), errorBody);
                        throw new LlmClientException("LLM API client error: " + response.statusCode() + " " + errorBody);
                    }
                    throw new LlmClientException("LLM API error: " + response.statusCode() + " " + errorBody);
                }

                return parseSseStream(response.body(), textTokenConsumer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM call interrupted", e);
            } catch (Exception e) {
                // 4xx 客户端错误不重试（参数非法、鉴权失败等，重试结果相同，只会消耗配额和时间）
                if (e instanceof LlmClientException lce) {
                    throw lce;
                }
                if (attempt == maxRetries) {
                    throw new RuntimeException("LLM call failed after " + maxRetries + " retries", e);
                }
                long delay = computeRetryDelayMs(attempt);
                log.warn("[LLM] attempt {}/{} failed: {} - {} (retry after {}ms)", attempt + 1, maxRetries, e.getClass().getSimpleName(), e.getMessage(), delay);
                log.debug("[LLM] exception details:", e);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("LLM call interrupted during retry backoff", ie);
                }
            }
        }
        return List.of();
    }

    /**
     * 计算重试退避：指数退避 retryDelayMs * 2^attempt，封顶 maxRetryDelayMs。
     * Math.min(attempt, 30) 防止 long 溢出（2^30 已是 10 亿级）。
     */
    private long computeRetryDelayMs(int attempt) {
        long raw = retryDelayMs * (1L << Math.min(attempt, 30));
        return Math.min(raw, maxRetryDelayMs);
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

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.default-api-url:https://open.bigmodel.cn/api/coding/paas/v4/chat/completions}")
    private String defaultApiUrl;

    @org.springframework.beans.factory.annotation.Value("${smart-query.llm.default-max-tokens:4096}")
    private int defaultMaxTokens;

    /**
     * 统一回退配置：当 models map（DB + yml）里都找不到 model 时使用。
     * 不再为具体 model 名（glm-5.1/glm-4 等）硬编码 case。
     * apiUrl 来自 default-api-url；apiKey 优先 env LLM_API_KEY，次选 GLM_API_KEY。
     */
    private LlmConfigEntity getDefaultConfig(String model) {
        LlmConfigEntity config = new LlmConfigEntity();
        config.setModelCode(model);

        String apiUrl = defaultApiUrl;
        String apiKey = System.getenv().getOrDefault("LLM_API_KEY", System.getenv("GLM_API_KEY"));
        if (apiKey == null) apiKey = "";

        config.setApiUrl(apiUrl);
        config.setApiKey(apiKey);
        config.setMaxTokens(defaultMaxTokens);
        config.setTemperature(java.math.BigDecimal.valueOf(0.1));

        log.debug("[LLM] getDefaultConfig for model={}, apiUrl={}, hasApiKey={}", model, apiUrl, !apiKey.isBlank());

        return config;
    }

    /**
     * 验证 messages 格式，避免非法格式导致 API 调用失败
     */
    private boolean validateMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            log.warn("[LLM] messages is null or empty");
            return false;
        }

        for (Map<String, Object> msg : messages) {
            if (msg == null) {
                log.warn("[LLM] message is null");
                return false;
            }

            String role = (String) msg.get("role");
            if (role == null || (!role.equals("system") && !role.equals("user") &&
                !role.equals("assistant") && !role.equals("tool"))) {
                log.warn("[LLM] invalid role: {}", role);
                return false;
            }

            // tool 角色必须有 tool_call_id
            if ("tool".equals(role) && msg.get("tool_call_id") == null) {
                log.warn("[LLM] tool message missing tool_call_id");
                return false;
            }

            // 检查 content 是否为异常长的字符串（可能是错误注入）
            Object content = msg.get("content");
            if (content instanceof String && ((String) content).length() > 50000) {
                log.warn("[LLM] content too long: {} chars", ((String) content).length());
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean isAvailable(String model) {
        try {
            resolveConfig(model);
            return true;
        } catch (Exception e) {
            log.warn("[LLM] model {} not available: {}", model, e.getMessage());
            return false;
        }
    }

    /**
     * 4xx 客户端错误专用异常 — 绕过 chatWithToolsStreaming 的重试循环。
     * 参数非法、鉴权失败、模型不存在等场景，重试结果相同，应立即失败。
     */
    public static class LlmClientException extends RuntimeException {
        LlmClientException(String message) {
            super(message);
        }
    }
}
