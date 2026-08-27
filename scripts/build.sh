#!/bin/bash
# 本地/虚拟机构建：Docker 多阶段构建后端、前端和 Python 执行镜像
set -e
cd "$(dirname "$0")/.."

echo "[build] 1/2 构建 docker 镜像（backend + frontend + python）..."
docker compose --profile tools build

echo "[build] 2/2 完成。镜像："
docker images --format '  {{.Repository}}:{{.Tag}} {{.Size}}' | grep -E "smart-query" || true
echo ""
echo "启动：docker compose up -d   （前端默认 http://localhost）"
