#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import sys
import os

# 设置输出编码为UTF-8
if sys.platform == 'win32':
    import codecs
    sys.stdout = codecs.getwriter('utf-8')(sys.stdout.buffer, 'strict')
    sys.stderr = codecs.getwriter('utf-8')(sys.stderr.buffer, 'strict')

"""创建测试训练数据"""
import pymysql
import pandas as pd
import numpy as np
from datetime import datetime, timedelta

print("=== 创建测试训练数据 ===")

# 连接数据库
conn = pymysql.connect(
    host='localhost',
    user='root',
    password='900110',
    database='smart_query_sample'
)

try:
    cursor = conn.cursor()

    # 删除已存在的测试表
    cursor.execute("DROP TABLE IF EXISTS test_training_data")
    print("[OK] 清理旧测试表")

    # 创建测试数据表
    create_table_sql = """
    CREATE TABLE test_training_data (
        id INT PRIMARY KEY AUTO_INCREMENT,
        age INT NOT NULL COMMENT '年龄',
        income DECIMAL(10,2) NOT NULL COMMENT '收入',
        spending_score INT NOT NULL COMMENT '消费评分',
        membership_years INT NOT NULL COMMENT '会员年数',
        satisfaction_score INT NOT NULL COMMENT '满意度评分',
        last_purchase_days INT NOT NULL COMMENT '上次购买天数',
        category_preference VARCHAR(50) COMMENT '类别偏好',
        churn_target INT NOT NULL COMMENT '是否流失(0=否,1=是)',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试训练数据表'
    """
    cursor.execute(create_table_sql)
    print("[OK] 创建测试表")

    # 生成测试数据 - 模拟客户流失预测数据
    np.random.seed(42)
    n_samples = 500

    data = []
    for i in range(n_samples):
        # 生成与流失相关的特征
        age = np.random.randint(18, 80)
        income = np.random.normal(50000, 20000)
        income = max(10000, min(150000, income))
        spending_score = np.random.randint(1, 101)
        membership_years = np.random.randint(1, 15)
        satisfaction_score = np.random.randint(1, 11)
        last_purchase_days = np.random.randint(1, 365)
        category_preference = np.random.choice(['电子产品', '家居', '服装', '食品', '其他'])

        # 根据特征生成流失概率
        churn_prob = 0.3  # 基础流失率
        churn_prob -= 0.02 * (satisfaction_score / 10)  # 满意度降低流失
        churn_prob -= 0.01 * (membership_years / 15)  # 会员年限降低流失
        churn_prob += 0.05 if last_purchase_days > 180 else 0  # 长时间未购买增加流失
        churn_prob += 0.03 if income < 30000 else 0  # 低收入增加流失

        churn_prob = max(0.1, min(0.9, churn_prob))  # 限制在10%-90%
        churn_target = 1 if np.random.random() < churn_prob else 0

        data.append((
            age, round(income, 2), spending_score, membership_years,
            satisfaction_score, last_purchase_days, category_preference, churn_target
        ))

    # 插入数据
    insert_sql = """
    INSERT INTO test_training_data
    (age, income, spending_score, membership_years, satisfaction_score, last_purchase_days, category_preference, churn_target)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
    """
    cursor.executemany(insert_sql, data)
    conn.commit()

    print(f"[OK] 插入 {n_samples} 条测试数据")

    # 显示数据统计
    cursor.execute("SELECT COUNT(*) as total, SUM(churn_target) as churned, AVG(age) as avg_age FROM test_training_data")
    stats = cursor.fetchone()
    print(f"[INFO] 总记录: {stats[0]}")
    print(f"[INFO] 流失数: {stats[1]}")
    print(f"[INFO] 流失率: {stats[1]/stats[0]*100:.1f}%")
    print(f"[INFO] 平均年龄: {stats[2]:.1f}")

    print("\n[SUCCESS] 测试数据创建完成！")
    print("现在你可以在数据挖掘管理中：")
    print("  1. 选择数据源: '员工业务数据库'")
    print("  2. 选择表: test_training_data")
    print("  3. 选择目标列: churn_target")
    print("  4. 选择特征列: age, income, spending_score, membership_years, satisfaction_score, last_purchase_days")
    print("  5. 选择算法: 随机森林")
    print("  6. 点击训练，观察代码步骤高亮效果")

except Exception as e:
    print(f"[ERROR] 创建数据失败: {e}")
finally:
    conn.close()
    print("\n[INFO] 数据库连接已关闭")
