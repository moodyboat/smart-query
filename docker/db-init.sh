#!/bin/bash
# MySQL 容器首次启动初始化：建两个库，示例数据导入 sample 库
# smart_query 系统库由后端 Flyway 在启动时迁移（V1-V16），这里不导系统 seed，避免与 Flyway 冲突
set -e

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<'SQL'
CREATE DATABASE IF NOT EXISTS smart_query       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS smart_query_sample DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SQL

# smart_query 系统库：schema + 种子（场景/算法等）由 seed 脚本导入（项目已移除 Flyway，不再自动迁移）
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" smart_query < /seeds/smart_query_seed.sql
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" smart_query_sample < /seeds/smart_query_sample_seed.sql

echo "[db-init] smart_query (schema+种子已导入) + smart_query_sample (示例数据已导入) 初始化完成"
