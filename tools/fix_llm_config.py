#!/usr/bin/env python3
"""
修复数据库中的LLM API URL配置
"""
import os
import subprocess
import sys

def fix_llm_config():
    """执行SQL修复数据库配置"""

    sql_commands = [
        # 查看当前配置
        "SELECT model_code, api_url, LEFT(api_key, 15) as key_preview FROM sq_llm_config WHERE deleted=0 AND status=1;",
        # 修复API URL
        "UPDATE sq_llm_config SET api_url = 'https://open.bigmodel.cn/api/coding/paas/v4/chat/completions', updated_at = NOW() WHERE model_code IN ('glm-4', 'glm-5.1') AND deleted=0;",
        # 验证修复结果
        "SELECT model_code, api_url, status FROM sq_llm_config WHERE model_code IN ('glm-4', 'glm-5.1') AND deleted=0;"
    ]

    print("正在修复LLM配置...")
    print("=" * 60)

    for i, sql in enumerate(sql_commands, 1):
        print(f"\n步骤 {i}: {sql[:50]}...")
        try:
            # 使用Windows的mysql命令
            result = subprocess.run(
                ['mysql', '-u', 'root', '-p900110', 'smart_query', '-e', sql],
                capture_output=True,
                text=True,
                encoding='gbk',  # Windows默认编码
                timeout=10
            )

            if result.returncode == 0:
                print(result.stdout)
            else:
                print(f"错误: {result.stderr}")

        except subprocess.CalledProcessError as e:
            print(f"执行失败: {e}")
        except Exception as e:
            print(f"异常: {e}")

    print("\n" + "=" * 60)
    print("配置修复完成！")

if __name__ == "__main__":
    try:
        fix_llm_config()
    except KeyboardInterrupt:
        print("\n操作已取消")
        sys.exit(1)
