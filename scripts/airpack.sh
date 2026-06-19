#!/bin/bash
# 离线打包（在联网环境运行）：mvn 打 jar + 构建全部镜像 + 导出为 tar，供拷到甲方无网络现场
# 产物：dist/smart-query-images.tar
set -e
cd "$(dirname "$0")/.."
MVN=tools/apache-maven-3.9.16/bin/mvn

mkdir -p dist

echo "[airpack] 1/4 打 jar..."
( cd backend && ../$MVN -q -DskipTests package )
cp backend/target/smart-query-*.jar backend/app.jar

echo "[airpack] 2/4 构建镜像（python + backend + frontend）..."
docker compose --profile tools build python
docker compose build

echo "[airpack] 3/4 导出镜像为 tar..."
# 只导甲方 docker compose up 实际需要的镜像：本项目 3 个 + mysql/redis。
# nginx/node/python 等是构建期 base，已烘焙进 smart-query-frontend/backend/python，甲方不重建故不需要。
IMAGES="smart-query-backend:latest smart-query-frontend:latest smart-query-python:latest mysql:8.0 redis:7-alpine"
docker save -o dist/smart-query-images.tar $IMAGES

echo "[airpack] 4/4 完成。"
ls -lh dist/smart-query-images.tar
echo ""
echo "下一步：把整个项目目录（含 dist/）拷到甲方现场，运行 ./scripts/airload.sh，再 docker compose up -d"
