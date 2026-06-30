#!/bin/bash
# 智能问数 - Docker 全栈停止（保留容器与数据，下次 ./docker-start.sh 秒起）
#
# 用法: ./docker-stop.sh
# 彻底清理（删容器、删卷）: docker compose down -v && docker rm -f dm8

set -euo pipefail
cd "$(dirname "$0")"

echo "=== 停止智能问数 Docker 服务 ==="
docker compose stop mysql redis backend frontend >/dev/null 2>&1 || true
docker stop dm8 >/dev/null 2>&1 || true
echo "  compose mysql/redis/backend/frontend + dm8 已停止"
echo ""
echo "重新启动: ./docker-start.sh"
