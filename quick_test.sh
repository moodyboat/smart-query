#!/bin/bash

echo "=== 提示词管理系统快速验证 ==="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}1. 验证后端服务状态${NC}"
BACKEND_STATUS=$(curl -s http://localhost:9000/api/v1/scenarios | jq -r '.code' 2>/dev/null)
if [ "$BACKEND_STATUS" = "200" ]; then
    echo -e "${GREEN}✓ 后端服务正常${NC}"
else
    echo -e "${RED}✗ 后端服务异常${NC}"
fi
echo ""

echo -e "${BLUE}2. 验证场景数据${NC}"
SCENARIO_COUNT=$(curl -s http://localhost:9000/api/v1/scenarios | jq '.data | length' 2>/dev/null)
echo "场景数量: $SCENARIO_COUNT"
if [ "$SCENARIO_COUNT" = "6" ]; then
    echo -e "${GREEN}✓ 场景数据完整${NC}"
else
    echo -e "${RED}✗ 场景数据不完整${NC}"
fi
echo ""

echo -e "${BLUE}3. 验证提示词模板${NC}"
PROMPT_NAME=$(curl -s http://localhost:9000/api/v1/prompt-templates/scenario/1 | jq -r '.data[0].name' 2>/dev/null)
echo "默认提示词: $PROMPT_NAME"
if [ -n "$PROMPT_NAME" ]; then
    echo -e "${GREEN}✓ 提示词模板正常${NC}"
else
    echo -e "${RED}✗ 提示词模板异常${NC}"
fi
echo ""

echo -e "${BLUE}4. 验证场景列表${NC}"
echo "可用场景:"
curl -s http://localhost:9000/api/v1/scenarios | jq -r '.data[] | "  - \(.name) (\(.code))"' 2>/dev/null
echo ""

echo -e "${BLUE}5. 测试场景API${NC}"
echo "测试销售分析场景:"
curl -s http://localhost:9000/api/v1/scenarios/code/sales_analysis | jq -r '.data.name' 2>/dev/null
echo ""

echo -e "${BLUE}6. 测试元数据API${NC}"
METADATA_STATUS=$(curl -s "http://localhost:9000/api/v1/metadata/type/business_term" | jq -r '.code' 2>/dev/null)
if [ "$METADATA_STATUS" = "200" ]; then
    echo -e "${GREEN}✓ 元数据API正常${NC}"
else
    echo -e "${RED}✗ 元数据API异常${NC}"
fi
echo ""

echo -e "${BLUE}7. 前端服务状态${NC}"
FRONTEND_CHECK=$(curl -s http://localhost:5174 | head -1 2>/dev/null)
if echo "$FRONTEND_CHECK" | grep -q "html"; then
    echo -e "${GREEN}✓ 前端服务正常 (http://localhost:5174)${NC}"
else
    echo -e "${RED}✗ 前端服务异常${NC}"
fi
echo ""

echo "=== 验证完成 ==="
echo ""
echo "📋 详细测试指南请查看: FEATURE_TEST_GUIDE.md"
echo "🌐 前端访问地址: http://localhost:5174"
echo "🔧 后端API地址: http://localhost:9000/api/v1"