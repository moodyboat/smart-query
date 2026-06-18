#!/bin/bash
# 智能问数 - 快速启动脚本

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"
MAVEN_HOME="$PROJECT_DIR/tools/apache-maven-3.9.16"
export PATH="$MAVEN_HOME/bin:$PATH"
FRONTEND_DIR="$PROJECT_DIR/frontend"
LOG_DIR="$PROJECT_DIR/logs"

mkdir -p "$LOG_DIR"

echo "=== 智能问数 启动 ==="

# 检查端口是否已占用
check_port() {
    if lsof -i :"$1" -t >/dev/null 2>&1; then
        echo "[WARN] 端口 $1 已被占用，尝试关闭..."
        lsof -i :"$1" -t | xargs kill -9 2>/dev/null || true
        sleep 1
    fi
}

# 启动后端
start_backend() {
    echo "[1/2] 启动后端 (Spring Boot :9000)..."
    check_port 9000
    cd "$BACKEND_DIR"
    nohup mvn spring-boot:run -q > "$LOG_DIR/backend.log" 2>&1 &
    BACKEND_PID=$!
    echo "  后端 PID: $BACKEND_PID"
    echo "$BACKEND_PID" > "$LOG_DIR/backend.pid"

    # 等待后端就绪
    echo "  等待后端启动..."
    for i in $(seq 1 30); do
        if curl -s http://localhost:9000/api/v1/conversation >/dev/null 2>&1; then
            echo "  后端已就绪 ✓"
            return 0
        fi
        sleep 1
    done
    echo "  [WARN] 后端启动超时，请检查 logs/backend.log"
    return 1
}

# 启动前端
start_frontend() {
    echo "[2/2] 启动前端 (Vite :5173)..."
    check_port 5173
    cd "$FRONTEND_DIR"

    # 检查 node_modules
    if [ ! -d "node_modules" ]; then
        echo "  安装前端依赖..."
        npm install --silent
    fi

    nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 &
    FRONTEND_PID=$!
    echo "  前端 PID: $FRONTEND_PID"
    echo "$FRONTEND_PID" > "$LOG_DIR/frontend.pid"

    # 等待前端就绪
    echo "  等待前端启动..."
    for i in $(seq 1 15); do
        if curl -s http://localhost:5173 >/dev/null 2>&1; then
            echo "  前端已就绪 ✓"
            return 0
        fi
        sleep 1
    done
    echo "  [WARN] 前端启动超时，请检查 logs/frontend.log"
    return 1
}

start_backend
start_frontend

echo ""
echo "=== 启动完成 ==="
echo "  前端: http://localhost:5173"
echo "  后端: http://localhost:9000"
echo "  日志: $LOG_DIR/"
echo ""
echo "停止服务: ./stop.sh"
