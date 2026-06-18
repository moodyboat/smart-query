-- 更新GLM API Key
-- 请将YOUR_NEW_API_KEY替换为新的API Key

UPDATE sq_llm_config
SET api_key = 'YOUR_NEW_API_KEY',
    updated_at = CURRENT_TIMESTAMP
WHERE model_code IN ('glm-5.1', 'glm-4-flash')
AND deleted = 0;

-- 验证更新
SELECT model_code, model_name,
       CASE
         WHEN api_key LIKE 'YOUR_NEW_API_KEY%' THEN '❌ 未更新'
         ELSE CONCAT('✅ 已更新: ', LEFT(api_key, 20), '...')
       END as api_key_status
FROM sq_llm_config
WHERE model_code IN ('glm-5.1', 'glm-4-flash')
AND deleted = 0;
