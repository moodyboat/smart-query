#!/bin/bash
# 本地构建：mvn 打 jar → 拷到 backend/app.jar → 构建全部 docker 镜像
set -e
cd "$(dirname "$0")/.."
MVN=tools/apache-maven-3.9.16/bin/mvn

echo "[build] 1/3 打 jar（跳过测试）..."
( cd backend && ../$MVN -q -DskipTests package )
cp backend/target/smart-query-*.jar backend/app.jar

echo "[build] 2/3 构建 docker 镜像（python + backend + frontend）..."
docker compose --profile tools build python
docker compose build

echo "[build] 3/3 完成。镜像："
docker images --format '  {{.Repository}}:{{.Tag}} {{.Size}}' | grep -E "smart-query" || true
echo ""
echo "启动：docker compose up -d   （前端 http://localhost:5173  后端 :9000）"
