# 安全规则

## SQL 安全
- 只允许执行 SELECT / SHOW / DESCRIBE / EXPLAIN 语句
- 严禁 DROP / DELETE / UPDATE / INSERT / ALTER / TRUNCATE / GRANT / REVOKE
- 所有查询自动添加 LIMIT 限制 (最多 1000 行)
- 查询超时限制: 30 秒

## Python 安全
- 禁止使用 os.system / subprocess / eval / exec
- 禁止文件系统操作 (os.remove / shutil.rmtree 等)
- 禁止网络请求 (requests / urllib 等，数据库连接除外)
- 代码执行超时限制: 60 秒 (可配置到 600 秒)

## 数据安全
- 不暴露数据库连接信息
- 查询结果自动截断过大字段
- 敏感数据 (密码、密钥) 不出现在结果中
