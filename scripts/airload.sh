#!/bin/bash
# 离线导入（在甲方无网络现场运行）：从 tar 加载全部镜像，之后即可 docker compose up
# 前置：项目目录（含 dist/smart-query-images.tar）已拷到现场，本机已装 Docker
set -e
cd "$(dirname "$0")/.."

if [ ! -f dist/smart-query-images.tar ]; then
  echo "❌ 未找到 dist/smart-query-images.tar，请先在联网环境运行 ./scripts/airpack.sh 打包"
  exit 1
fi

echo "[airload] 从 tar 加载镜像..."
docker load -i dist/smart-query-images.tar

echo "[airload] 完成。已加载镜像："
docker images --format '  {{.Repository}}:{{.Tag}} {{.Size}}' | grep -E "smart-query|mysql|redis|nginx" || true
echo ""
echo "下一步：docker compose up （现场无需联网，镜像已全部就位）"
