#!/usr/bin/env python3
"""检查数据库中的LLM配置"""
import sys
import os

# 添加JDBC驱动路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'backend', 'target', 'classes'))

try:
    import jpype
    import jpype.dbutils as dbutils

    if not jpype.isJVMStarted():
        # 尝试使用MySQL JDBC驱动
        jdbc_jar = os.path.join(os.path.dirname(__file__), '..', 'backend', 'target', 'dependency', 'mysql-*.jar')
        jpype.addClassPath(jdbc_jar)
        jpype.startJVM()

    conn = dbutils.connect(
        'jdbc:mysql://localhost:3306/smart_query',
        {'user': 'root', 'password': '900110'}
    )

    cursor = conn.createStatement()
    rs = cursor.executeQuery(
        "SELECT model_code, api_url, LEFT(api_key, 20) as api_key_prefix, max_tokens, temperature, status " +
        "FROM sq_llm_config WHERE deleted=0 AND status=1"
    )

    print("数据库中的LLM配置:")
    print("-" * 80)
    while rs.next():
        print(f"模型: {rs.getString('model_code')}")
        print(f"  API URL: {rs.getString('api_url')}")
        print(f"  API Key: {rs.getString('api_key_prefix')}...")
        print(f"  Max Tokens: {rs.getInt('max_tokens')}")
        print(f"  Temperature: {rs.getFloat('temperature')}")
        print(f"  Status: {rs.getInt('status')}")
        print("-" * 80)

    rs.close()
    conn.close()

except Exception as e:
    print(f"错误: {e}")
    print("\n请手动检查数据库:")
    print("1. 打开MySQL Workbench或其他数据库工具")
    print("2. 连接到 localhost:3306/smart_query (用户名:root, 密码:900110)")
    print("3. 执行: SELECT model_code, api_url, LEFT(api_key, 20) FROM sq_llm_config WHERE deleted=0")
