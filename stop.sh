#!/bin/bash
# 智能问数 - 快速停止脚本

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_DIR/logs"

echo "=== 智能问数 停止 ==="

# 停止后端
stop_service() {
    local name="$1"
    local port="$2"
    local pid_file="$LOG_DIR/$name.pid"

    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            echo "  停止 $name (PID: $pid)..."
            kill "$pid" 2>/dev/null || true
            rm -f "$pid_file"
            echo "  $name 已停止 ✓"
            return 0
        fi
        rm -f "$pid_file"
    fi

    # 兜底: 按端口杀进程
    local pids=$(lsof -i :"$port" -t 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "  按端口停止 $name (:${port})..."
        echo "$pids" | xargs kill 2>/dev/null || true
        echo "  $name 已停止 ✓"
    else
        echo "  $name 未运行"
    fi
}

stop_service "backend" 9000
stop_service "frontend" 5173

echo ""
echo "=== 已停止 ==="
