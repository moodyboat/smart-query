#!/bin/bash
# 离线打包（在联网环境运行）：构建全部镜像并导出为 tar，供无网络现场导入
# 产物：dist/smart-query-images.tar
set -e
cd "$(dirname "$0")/.."
mkdir -p dist

echo "[airpack] 1/3 构建镜像（backend + frontend + python）..."
docker compose --profile tools build

echo "[airpack] 2/3 导出镜像为 tar..."
# 从 Compose 的最终配置读取镜像名，自动包含 .env 中的私有仓库/自定义标签。
# nginx/node/python 等是构建期 base，已烘焙进 smart-query-frontend/backend/python，甲方不重建故不需要。
IMAGES="$(docker compose --profile tools config --images)"
docker save -o dist/smart-query-images.tar $IMAGES

echo "[airpack] 3/3 完成。"
ls -lh dist/smart-query-images.tar
echo ""
echo "下一步：把整个项目目录（含 dist/）拷到甲方现场，运行 ./scripts/airload.sh，再 docker compose up -d"
