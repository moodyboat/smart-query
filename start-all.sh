#!/bin/bash
# 智能问数 + 指标引擎 - 完整启动脚本

echo "=== 智能问数 + 指标引擎 完整启动 ==="
echo ""

# 检查端口占用
check_port() {
    local port=$1
    local service=$2
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1 ; then
        echo "⚠️  端口 $port 已被占用 ($service)"
        return 1
    fi
    return 0
}

# 检查所有端口
ports_ok=true
check_port 8080 "智能问数后端" || ports_ok=false
check_port 21050 "指标引擎后端" || ports_ok=false
check_port 5173 "智能问数前端" || ports_ok=false
check_port 5174 "指标引擎前端" || ports_ok=false

if [ "$ports_ok" = false ]; then
    echo ""
    echo "❌ 部分端口已被占用，请先关闭相关进程"
    echo "使用 ./stop-all.sh 停止所有服务"
    exit 1
fi

echo "✓ 所有端口检查通过"
echo ""

# 1. 启动智能问数后端
echo "[1/4] 启动智能问数后端 (Spring Boot :8080)..."
cd backend
mvn spring-boot:run > ../logs/smart-query-backend.log 2>&1 &
BACKEND_PID=$!
echo "  后端 PID: $BACKEND_PID"
cd ..

# 等待后端启动
echo "  等待后端启动..."
timeout=60
while [ $timeout -gt 0 ]; do
    if curl -s http://localhost:8080/actuator/health >/dev/null 2>&1; then
        echo "  后端已就绪 ✓"
        break
    fi
    sleep 1
    timeout=$((timeout-1))
done
if [ $timeout -eq 0 ]; then
    echo "  ❌ 后端启动超时"
    exit 1
fi

# 2. 启动指标引擎后端
echo ""
echo "[2/4] 启动指标引擎后端 (Spring Boot :21050)..."
cd 指标引擎/backend/gdgp-metric/gdgp-metric-provider
mvn spring-boot:run > ../../../../logs/metric-engine-backend.log 2>&1 &
METRIC_BACKEND_PID=$!
echo "  指标引擎后端 PID: $METRIC_BACKEND_PID"
cd ../../..

# 等待指标引擎后端启动
echo "  等待指标引擎后端启动..."
timeout=60
while [ $timeout -gt 0 ]; do
    if curl -s http://localhost:21050/api/actuator/health >/dev/null 2>&1; then
        echo "  指标引擎后端已就绪 ✓"
        break
    fi
    sleep 1
    timeout=$((timeout-1))
done
if [ $timeout -eq 0 ]; then
    echo "  ❌ 指标引擎后端启动超时"
    exit 1
fi

# 3. 启动智能问数前端
echo ""
echo "[3/4] 启动智能问数前端 (Vite :5173)..."
cd frontend
npm run dev > ../logs/smart-query-frontend.log 2>&1 &
FRONTEND_PID=$!
echo "  前端 PID: $FRONTEND_PID"
cd ..

# 等待前端启动
echo "  等待前端启动..."
sleep 3
if curl -s http://localhost:5173 >/dev/null 2>&1; then
    echo "  前端已就绪 ✓"
else
    echo "  ⚠️  前端可能仍在启动中..."
fi

# 4. 启动指标引擎前端
echo ""
echo "[4/4] 启动指标引擎前端 (Vite :5174)..."
cd "指标引擎/frontend/metric-app"
npm run dev > ../../../../logs/metric-engine-frontend.log 2>&1 &
METRIC_FRONTEND_PID=$!
echo "  指标引擎前端 PID: $METRIC_FRONTEND_PID"
cd ../../..

# 等待指标引擎前端启动
echo "  等待指标引擎前端启动..."
sleep 3
if curl -s http://localhost:5174 >/dev/null 2>&1; then
    echo "  指标引擎前端已就绪 ✓"
else
    echo "  ⚠️  指标引擎前端可能仍在启动中..."
fi

# 保存PID到文件
cat > .pids <<EOF
SMART_QUERY_BACKEND=$BACKEND_PID
METRIC_ENGINE_BACKEND=$METRIC_BACKEND_PID
SMART_QUERY_FRONTEND=$FRONTEND_PID
METRIC_ENGINE_FRONTEND=$METRIC_FRONTEND_PID
EOF

echo ""
echo "=== 启动完成 ==="
echo ""
echo "🎯 访问地址："
echo "  智能问数:     http://localhost:5173"
echo "  指标引擎:     http://localhost:5174"
echo ""
echo "📋 后端健康检查："
echo "  智能问数后端: http://localhost:8080/actuator/health"
echo "  指标引擎后端: http://localhost:21050/api/actuator/health"
echo ""
echo "📝 日志文件："
echo "  logs/smart-query-backend.log"
echo "  logs/metric-engine-backend.log"
echo "  logs/smart-query-frontend.log"
echo "  logs/metric-engine-frontend.log"
echo ""
echo "🛑 停止服务: ./stop-all.sh"
echo ""
