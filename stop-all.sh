#!/bin/bash
# 智能问数 + 指标引擎 - 完整停止脚本

echo "=== 智能问数 + 指标引擎 停止 ==="
echo ""

# 函数：安全停止进程
safe_stop() {
    local pid=$1
    local name=$2
    if [ -z "$pid" ]; then
        echo "  $name 未运行"
        return
    fi

    if ps -p $pid > /dev/null 2>&1; then
        echo "  停止 $name (PID: $pid)..."
        kill $pid 2>/dev/null
        sleep 2

        # 如果进程仍在运行，强制停止
        if ps -p $pid > /dev/null 2>&1; then
            echo "  强制停止 $name..."
            kill -9 $pid 2>/dev/null
        fi
    else
        echo "  $name 未运行 (PID: $pid)"
    fi
}

# 尝试从文件读取PID
if [ -f .pids ]; then
    source .pids
    rm -f .pids
fi

# 1. 停止智能问数前端
echo "[1/4] 停止智能问数前端..."
if [ -n "$SMART_QUERY_FRONTEND" ]; then
    safe_stop $SMART_QUERY_FRONTEND "智能问数前端"
else
    FRONTEND_PIDS=$(lsof -t -i:5173 2>/dev/null)
    if [ -n "$FRONTEND_PIDS" ]; then
        for pid in $FRONTEND_PIDS; do
            safe_stop $pid "智能问数前端 (PID: $pid)"
        done
    else
        echo "  智能问数前端 未运行"
    fi
fi

# 2. 停止指标引擎前端
echo ""
echo "[2/4] 停止指标引擎前端..."
if [ -n "$METRIC_ENGINE_FRONTEND" ]; then
    safe_stop $METRIC_ENGINE_FRONTEND "指标引擎前端"
else
    METRIC_FRONTEND_PIDS=$(lsof -t -i:5174 2>/dev/null)
    if [ -n "$METRIC_FRONTEND_PIDS" ]; then
        for pid in $METRIC_FRONTEND_PIDS; do
            safe_stop $pid "指标引擎前端 (PID: $pid)"
        done
    else
        echo "  指标引擎前端 未运行"
    fi
fi

# 3. 停止智能问数后端
echo ""
echo "[3/4] 停止智能问数后端..."
if [ -n "$SMART_QUERY_BACKEND" ]; then
    safe_stop $SMART_QUERY_BACKEND "智能问数后端"
else
    BACKEND_PIDS=$(lsof -t -i:8080 2>/dev/null)
    if [ -n "$BACKEND_PIDS" ]; then
        for pid in $BACKEND_PIDS; do
            safe_stop $pid "智能问数后端 (PID: $pid)"
        done
    else
        echo "  智能问数后端 未运行"
    fi
fi

# 4. 停止指标引擎后端
echo ""
echo "[4/4] 停止指标引擎后端..."
if [ -n "$METRIC_ENGINE_BACKEND" ]; then
    safe_stop $METRIC_ENGINE_BACKEND "指标引擎后端"
else
    METRIC_BACKEND_PIDS=$(lsof -t -i:21050 2>/dev/null)
    if [ -n "$METRIC_BACKEND_PIDS" ]; then
        for pid in $METRIC_BACKEND_PIDS; do
            safe_stop $pid "指标引擎后端 (PID: $pid)"
        done
    else
        echo "  指标引擎后端 未运行"
    fi
fi

echo ""
echo "=== 已停止 ==="
echo ""
