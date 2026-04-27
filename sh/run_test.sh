#!/bin/bash

# 测试运行脚本 - 自动保存日志
# 用法:
#   ./run_test.sh                          # 运行所有测试
#   ./run_test.sh HAStateMachineTest       # 运行指定测试类
#   ./run_test.sh "HAStateMachineTest,HARollingUpgradeTest"  # 运行多个测试类

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
PROJECT_DIR=$(dirname "$SCRIPT_DIR")
LOG_DIR="$PROJECT_DIR/test-logs"

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

mkdir -p "$LOG_DIR"

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
TEST_CLASS="${1:-ALL}"

# 日志文件名
SAFE_NAME=$(echo "$TEST_CLASS" | tr ',' '_' | tr ' ' '_')
LOG_FILE="$LOG_DIR/${TIMESTAMP}_${SAFE_NAME}.log"

echo -e "${YELLOW}运行测试: $TEST_CLASS${NC}"
echo -e "${YELLOW}日志保存: $LOG_FILE${NC}"
echo ""

cd "$PROJECT_DIR"

if [ "$TEST_CLASS" = "ALL" ]; then
    mvn test 2>&1 | tee "$LOG_FILE"
else
    mvn test -Dtest="$TEST_CLASS" 2>&1 | tee "$LOG_FILE"
fi

EXIT_CODE=${PIPESTATUS[0]}

echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✅ 测试通过，日志已保存: $LOG_FILE${NC}"
else
    echo -e "${RED}❌ 测试失败，日志已保存: $LOG_FILE${NC}"
fi

# 打印摘要
echo ""
echo "=== 测试摘要 ==="
grep -E "Tests run:|BUILD" "$LOG_FILE" | tail -10

exit $EXIT_CODE
