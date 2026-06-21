#!/bin/bash
# 业务库示例容器（mysql）首次启动初始化：只建 smart_query_sample 示例库
#
# 重要：smart_query 系统库不在 mysql 容器里建，由 DM8 端独立导入：
#   disql SYSDBA/Dameng123@localhost:5236 -e "CREATE SCHEMA SMART_QUERY"
#   disql SYSDBA/Dameng123@localhost:5236 smart_query < smart_query_seed.sql
# mysql 容器仅作"业务库示例数据"演示用，不是系统库。
set -e

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<'SQL'
CREATE DATABASE IF NOT EXISTS smart_query_sample DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SQL

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" smart_query_sample < /seeds/smart_query_sample_seed.sql

echo "[db-init] smart_query_sample (业务库示例数据已导入) 初始化完成"
echo "[db-init] 系统库 smart_query 应建在 DM8（host.docker.internal:5236 schema SMART_QUERY），不在本 mysql 容器"
