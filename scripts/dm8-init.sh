#!/bin/bash
# DM8 系统库初始化：清洗 MySQL dump → 灌入 DM8（COMPATIBLE_MODE=4 + CASE_SENSITIVE=0）
#
# 背景：backend/db-export/smart_query_seed.sql 是 mysqldump 出的 MySQL 语法，
# DM8 的 MySQL 兼容模式（COMPATIBLE_MODE=4）对反引号/LOCK/ENGINE/ENUM/FULLTEXT/
# ON UPDATE 等支持不全，必须先清洗。且 DM8 实例必须 CASE_SENSITIVE=0，
# 否则反引号小写列名与 JDBC 大写查询不匹配（CLAUDE.md 声称的配置）。
#
# 前置条件：
#   1. DM8 实例已用 CASE_SENSITIVE=0 + COMPATIBLE_MODE=4 初始化
#      （见 docker/dm8-entrypoint.sh，dminit 带 CASE_SENSITIVE=0，
#       并 sed dm.ini 的 COMPATIBLE_MODE=4）
#   2. DM8 可达：默认 127.0.0.1:5236，SYSDBA/Dameng123
#
# 用法：
#   scripts/dm8-init.sh                       # 默认 127.0.0.1:5236
#   DM_HOST=192.168.1.10 scripts/dm8-init.sh  # 自定义 DM8 地址
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SEED_SRC="$PROJECT_DIR/backend/db-export/smart_query_seed.sql"
DM_HOST="${DM_HOST:-127.0.0.1}"
DM_PORT="${DM_PORT:-5236}"
DM_USER="${DM_USER:-SYSDBA}"
DM_PASS="${DM_PASS:-Dameng123}"
SCHEMA="${SCHEMA:-smart_query}"

[ -f "$SEED_SRC" ] || { echo "缺种子文件: $SEED_SRC"; exit 1; }

echo "=== DM8 系统库初始化 ($DM_HOST:$DM_PORT, schema=$SCHEMA) ==="

# 1) 清洗 MySQL dump → DM8 兼容
CLEAN=$(mktemp -t dm8_seed_XXXXXX.sql)
python3 - "$SEED_SRC" "$CLEAN" <<'PY'
import re, sys
with open(sys.argv[1], 'r', encoding='utf-8') as f:
    src = f.read()
src = re.sub(r'^/\*![0-9]{5}[^\n]*\n', '', src, flags=re.M)        # MySQL 条件注释
src = re.sub(r'^LOCK TABLES[^\n]*\n', '', src, flags=re.M)          # LOCK TABLES
src = re.sub(r'^UNLOCK TABLES;\n', '', src, flags=re.M)            # UNLOCK TABLES
src = re.sub(r'\)\s*ENGINE=InnoDB[^;]*;', ');', src)               # 表级 ENGINE= 子句
src = re.sub(r'\s+ON UPDATE CURRENT_TIMESTAMP', '', src)           # ON UPDATE（DM8 不支持）
src = re.sub(r'\s+CHARACTER SET \w+', '', src)                     # 列级字符集
src = re.sub(r'\s+COLLATE \w+', '', src)                           # 列级 collation
src = re.sub(r"\benum\([^)]*\)", "VARCHAR(50)", src, flags=re.I)   # ENUM → VARCHAR
src = re.sub(r'\b(tinyint|smallint|mediumint|int|bigint)\(\d+\)', r'\1', src, flags=re.I)  # 整型去长度
src = re.sub(r'\bUNIQUE KEY\s+(`\w+`|\w+)\s*(\([^)]*\))', r'UNIQUE \2', src)  # UNIQUE KEY → UNIQUE
src = src.replace('`', '')                                          # 全去反引号（CASE_SENSITIVE=0 下安全）
lines = [ln for ln in src.split('\n')
         if not re.match(r'^\s*(FULLTEXT KEY|FULLTEXT|KEY)\s+\w+\s*\(', ln)]   # 删索引行
src = '\n'.join(lines)
lines = [ln for ln in src.split('\n')
         if not re.match(r'^\s*CONSTRAINT\s+\w+\s+FOREIGN KEY', ln)
         and not re.match(r'^\s*FOREIGN KEY\s*\(', ln)]                        # 删外键约束（DM8 兼容性差）
src = '\n'.join(lines)
src = re.sub(r',(\s*\n\s*)\)', r'\1)', src)                         # 修删行后孤立逗号
src = re.sub(r'(?m)^(\s+)(model)(\s+)', r'\1"\2"\3', src)           # model 保留字列名加双引号
src = re.sub(r'\bmodel\b(?=\s*[,)])', '"model"', src)               # INSERT 列清单里的 model
with open(sys.argv[2], 'w', encoding='utf-8') as f:
    f.write(src)
PY

# 2) 重建 schema
DISQL="/opt/dmdbms/bin/disql $DM_USER/$DM_PASS@$DM_HOST:$DM_PORT"
docker exec -i dm8 bash -lc "$DISQL -e 'DROP SCHEMA $SCHEMA CASCADE;'" 2>&1 | tail -1 || true
docker exec -i dm8 bash -lc "$DISQL -e 'CREATE SCHEMA $SCHEMA;'" 2>&1 | tail -1

# 3) stdin 灌入（UTF-8，避免 disql 文件读取 GBK 乱码）
{ echo "SET SCHEMA $SCHEMA;"; cat "$CLEAN"; } | \
  docker exec -i dm8 bash -lc "LANG=zh_CN.UTF-8 $DISQL 2>&1" | \
  grep -iE "^\[-|invalid|nearby" | head -20 || echo "（无错误）"

# 4) 验证
echo "=== 表数 ==="
docker exec -i dm8 bash -lc "$DISQL 2>&1" <<EOSQL | grep -E "COUNT|sq_"
SET SCHEMA $SCHEMA;
SELECT COUNT(*) FROM USER_TABLES;
SELECT COUNT(*) AS ALGO FROM sq_algorithm;
SELECT COUNT(*) AS SCENARIO FROM sq_scenario;
EXIT
EOSQL

rm -f "$CLEAN"
echo "=== DM8 系统库初始化完成 ==="
