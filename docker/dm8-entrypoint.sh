#!/bin/bash
# DM8 容器 entrypoint：初始化实例时强制 CASE_SENSITIVE=0 + COMPATIBLE_MODE=4
#
# 背景：dm8:latest 镜像自带的 entrypoint.sh 用默认参数 dminit，实例是
# CASE_SENSITIVE=1（大小写敏感）+ COMPATIBLE_MODE=0（Oracle），导致：
#   - smart_query_seed.sql 的反引号小写列名与 JDBC 大写查询不匹配
#   - MySQL 语法（反引号/AUTO_INCREMENT/ENUM）不被解析
# 本脚本覆盖 entrypoint，强制项目所需的 DM8 配置。
#
# 部署用法（docker-compose.yml 的 dm8 服务）：
#   volumes:
#     - ./docker/dm8-entrypoint.sh:/entrypoint.sh:ro
#   或者 docker run：
#   docker run -d -p 5236:5236 \
#     -v $(pwd)/docker/dm8-entrypoint.sh:/entrypoint.sh:ro \
#     dm8:latest
set -e

if [ ! -d "/opt/dmdbms/data/DAMENG" ]; then
    echo "Initializing DM8 database (CASE_SENSITIVE=0, COMPATIBLE_MODE=4)..."
    /opt/dmdbms/bin/dminit \
        PATH=/opt/dmdbms/data \
        DB_NAME=DAMENG \
        INSTANCE_NAME=DMSERVER \
        PORT_NUM=5236 \
        SYSDBA_PWD=Dameng123 \
        SYSAUDITOR_PWD=Dameng123 \
        CHARSET=1 \
        PAGE_SIZE=16 \
        EXTENT_SIZE=16 \
        LOG_SIZE=256 \
        TIME_ZONE="+08:00" \
        CASE_SENSITIVE=0
    # COMPATIBLE_MODE 是 dm.ini 参数，dminit 后 sed 改
    sed -i 's/^\(\s*\)COMPATIBLE_MODE\s*=.*/\1COMPATIBLE_MODE                 = 4/' /opt/dmdbms/data/DAMENG/dm.ini
    echo "Database initialized (CASE_SENSITIVE=0 via dminit, COMPATIBLE_MODE=4 via dm.ini)."
fi

echo "Starting DM8 server..."
/opt/dmdbms/bin/dmserver /opt/dmdbms/data/DAMENG/dm.ini -noconsole &
SERVER_PID=$!
for i in $(seq 1 30); do
    if /opt/dmdbms/bin/disql SYSDBA/Dameng123@localhost:5236 -e "SELECT 1;" >/dev/null 2>&1; then
        echo "DM8 ready on port 5236 (SYSDBA/Dameng123)"
        break
    fi
    sleep 2
done
wait $SERVER_PID
