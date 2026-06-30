#!/bin/bash
# 业务库示例容器（mysql）首次启动初始化：只建 smart_query_sample 示例库
#
# 重要：smart_query 系统库不在 mysql 容器里建，由 DM8 端独立导入。
# mysql 容器仅作"业务库示例数据"演示用，不是系统库。
#
# DM8 系统库初始化（完整流程）：
#   1. DM8 实例必须 CASE_SENSITIVE=0 + COMPATIBLE_MODE=4
#      → 用 docker/dm8-entrypoint.sh 覆盖容器 entrypoint
#   2. 清洗 MySQL dump 并灌入（smart_query_seed.sql 是 MySQL 语法，不能直接灌）
#      → scripts/dm8-init.sh（自动清洗 + 导入 + 验证）
#   3. DataSeeder 启动时兜底建 sq_user/sq_role_scenario + 注入默认 admin
#
# 切勿直接 `disql < smart_query_seed.sql`：反引号/LOCK/ENGINE/ENUM/FULLTEXT 等
# MySQL 语法 DM8 兼容模式吃不全，会连锁失败。
set -e

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<'SQL'
CREATE DATABASE IF NOT EXISTS smart_query_sample DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SQL

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" smart_query_sample < /seeds/smart_query_sample_seed.sql

echo "[db-init] smart_query_sample (业务库示例数据已导入) 初始化完成"
echo "[db-init] 系统库 smart_query 应建在 DM8（host.docker.internal:5236 schema SMART_QUERY），不在本 mysql 容器"
