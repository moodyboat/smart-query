#!/bin/bash
# 智能问数 - Docker 全栈一键启动
#
# 用法: ./docker-start.sh
#
# 启动顺序: Docker daemon → DM8(:5236) → compose(mysql+redis+backend+frontend) → 健康检查
#
# 访问地址:
#   前端  http://localhost:5174   账号 admin / admin123
#   后端  http://localhost:9001
#   DM8   localhost:5236  (SYSDBA/Dameng123, schema=SMART_QUERY)
#
# 已封装的坑（都踩过）:
#   1. Docker Desktop 守护进程没起 → 自动 open -a Docker 并轮询等待
#   2. DM8 容器端口"配置了但实际未发布"残留（NetworkSettings.Ports 为空数组）
#      → 检测到就强制删除重建，保证 -p 5236:5236 真正生效
#   3. 后端探活用 curl -sf 会把 401（需鉴权）误判为失败
#      → 改用 HTTP 状态码判定，401/200 都算就绪
#
# DM8 数据来源:
#   优先用快照镜像 dm8:smartquery-snap（commit 自带 57 表 + 种子数据），
#   无则回退到 dm8:latest + docker/dm8-entrypoint.sh 从零初始化并灌种子。
#   生成快照: docker commit dm8 dm8:smartquery-snap

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

DM8_CONTAINER="dm8"
DM8_SNAP_IMAGE="dm8:smartquery-snap"
DM8_BASE_IMAGE="dm8:latest"
DM8_PORT=5236
BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-9001}"
FRONTEND_HOST_PORT="${FRONTEND_HOST_PORT:-5174}"

c_ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
c_warn() { printf "  \033[33m!\033[0m %s\n" "$1"; }
c_fail() { printf "  \033[31m✗\033[0m %s\n" "$1"; }

# ---------- 0. Docker daemon ----------
if ! docker info >/dev/null 2>&1; then
  echo "[0/4] Docker daemon 未运行，启动 Docker Desktop..."
  open -a Docker
  for i in $(seq 1 60); do
    if docker info >/dev/null 2>&1; then break; fi
    [ "$i" = "60" ] && { c_fail "Docker daemon 启动超时"; exit 1; }
    sleep 2
  done
fi
echo "[0/4] Docker daemon 就绪"

# ---------- 1. DM8 系统库 ----------
echo "[1/4] DM8 系统库(:${DM8_PORT})"

# 1a. 容器存在但端口没发布（残留状态）→ 重建
if docker ps -a --format '{{.Names}}' | grep -qx "$DM8_CONTAINER"; then
  ports_json=$(docker inspect "$DM8_CONTAINER" --format '{{json .NetworkSettings.Ports}}' 2>/dev/null || echo '{}')
  if ! echo "$ports_json" | grep -q "HostPort"; then
    c_warn "$DM8_CONTAINER 端口未实际发布（配置残留），删除重建"
    docker rm -f "$DM8_CONTAINER" >/dev/null
  fi
fi

# 1b. 容器不在 → 用快照镜像或基础镜像创建
if ! docker ps -a --format '{{.Names}}' | grep -qx "$DM8_CONTAINER"; then
  if docker image inspect "$DM8_SNAP_IMAGE" >/dev/null 2>&1; then
    c_ok "用快照镜像 $DM8_SNAP_IMAGE 创建（含种子数据）"
    docker run -d --name "$DM8_CONTAINER" -p "${DM8_PORT}:${DM8_PORT}" "$DM8_SNAP_IMAGE" >/dev/null
  elif docker image inspect "$DM8_BASE_IMAGE" >/dev/null 2>&1; then
    c_warn "无快照镜像，用 $DM8_BASE_IMAGE + 项目 entrypoint 从零初始化（首次较慢）"
    docker run -d --name "$DM8_CONTAINER" -p "${DM8_PORT}:${DM8_PORT}" \
      -v "$PROJECT_DIR/docker/dm8-entrypoint.sh:/entrypoint.sh:ro" \
      "$DM8_BASE_IMAGE" >/dev/null
    NEED_SEED=1
  else
    c_fail "缺少 DM8 镜像（$DM8_SNAP_IMAGE 和 $DM8_BASE_IMAGE 都没有）"
    exit 1
  fi
else
  docker start "$DM8_CONTAINER" >/dev/null 2>&1 || true
fi

# 1c. 等 DM8 实例响应
echo "  等 DM8 就绪..."
for i in $(seq 1 30); do
  if docker exec "$DM8_CONTAINER" bash -lc \
    '/opt/dmdbms/bin/disql SYSDBA/Dameng123@localhost:5236 -e "SELECT 1;" >/dev/null 2>&1'; then
    c_ok "DM8 ready"; break
  fi
  if [ "$i" = "30" ]; then c_fail "DM8 启动超时"; docker logs "$DM8_CONTAINER" --tail 20; exit 1; fi
  sleep 2
done

# 1d. 首次从零初始化 → 灌种子
if [ "${NEED_SEED:-0}" = "1" ]; then
  echo "  灌种子数据(scripts/dm8-init.sh)..."
  bash "$PROJECT_DIR/scripts/dm8-init.sh" || { c_fail "灌种子失败"; exit 1; }
fi

# ---------- 2. compose ----------
echo "[2/4] docker compose up(mysql redis backend frontend)"
docker compose up -d mysql redis backend frontend >/dev/null
c_ok "compose 服务已启动"

# ---------- 3. 后端探活 ----------
echo "[3/4] 等后端就绪(:${BACKEND_HOST_PORT})"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${BACKEND_HOST_PORT}/api/v1/auth/me" 2>/dev/null || echo "000")
  if [ "$code" = "401" ] || [ "$code" = "200" ]; then
    c_ok "backend ready (HTTP $code)"; break
  fi
  if [ "$i" = "60" ]; then
    c_fail "backend 启动超时，查日志: docker logs v3-backend-1 --tail 50"
    exit 1
  fi
  sleep 3
done

# ---------- 4. 完成 ----------
echo "[4/4] 启动完成"
echo ""
echo "  前端:    http://localhost:${FRONTEND_HOST_PORT}   (admin / admin123)"
echo "  后端:    http://localhost:${BACKEND_HOST_PORT}"
echo "  DM8:      localhost:${DM8_PORT} (SYSDBA/Dameng123, schema=SMART_QUERY)"
echo ""
echo "  日志: docker compose logs -f backend"
echo "  停止: ./docker-stop.sh"
