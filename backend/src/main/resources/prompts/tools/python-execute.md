# python_execute - Python 数据挖掘

## 描述
执行 Python 代码进行数据分析、统计建模、机器学习等任务。Python 环境已安装 pandas、numpy、matplotlib、scikit-learn、sqlalchemy 等库。

## 使用场景
- 复杂数学计算和统计分析
- 机器学习建模和预测
- 数据清洗和转换
- 生成分析图表（matplotlib）
- SQL 难以完成的复杂计算
- 多步数据处理和计算流水线

## 数据库连接
代码执行时自动注入数据库连接变量 `engine`（SQLAlchemy Engine）。

```python
import pandas as pd

# 直接读取数据库数据
df = pd.read_sql("SELECT * FROM orders WHERE amount > 1000", engine)
print(f"数据量: {len(df)}")
print(df.describe())
```

## 工作区 (Workspace)
每次对话有独立的工作目录，变量 `_workspace` 自动注入为工作区路径。

```python
# 保存中间数据供后续调用使用
df.to_csv(f"{_workspace}/cleaned_data.csv", index=False)

# 在后续调用中重新加载
df = pd.read_csv(f"{_workspace}/cleaned_data.csv")
```

这样可以在多次执行间传递数据，无需每次重新查询数据库。

## 编写指南
1. **每次调用是独立进程，变量不跨调用保留**。数据查询和绘图/分析必须写在同一次调用中。如需跨调用传递数据，通过工作区文件（CSV/Pickle）中转
2. 代码必须完整可执行（包含所有 import）
2. 以下模块已自动注入，无需 import 即可使用: `pandas`(pd), `numpy`(np), `matplotlib.pyplot`(plt), `json`, `os`, `sys`, `engine`(数据库连接)
3. 使用 `print()` 输出分析结果
4. 图表使用 `matplotlib` 自动保存（`plt.show()` 和 `plt.savefig()` 都会自动追踪为图片产物）
5. 大数据集使用分块读取（chunksize）
6. 变量命名清晰，代码结构分明
7. 用注释分隔不同分析步骤
8. 注意: 不要写 `import pandas`、`import numpy`、`import os` 等，这些已自动导入。尝试 import 这些模块会收到提示

## 安全限制
禁止以下操作:
- os.system / subprocess / exec / eval
- 文件删除（os.remove, shutil.rmtree）
- 网络请求（requests, urllib，除数据库连接外）
- 系统命令执行
- 文件系统遍历

## 超时
默认超时 60 秒，最大 600 秒。长时间运行的任务建议分步执行。

## 迭代调试模式（重要）
当代码执行出错时，你可以看到完整的错误信息。请按以下步骤调试:

### 步骤 1: 阅读错误信息
错误信息包含:
- **错误类型**: ImportError / NameError / TypeError / ValueError / KeyError 等
- **错误位置**: 文件名和行号
- **错误详情**: 具体的错误描述

### 步骤 2: 分析错误原因
| 错误类型 | 常见原因 | 修复方法 |
|---------|---------|---------|
| ImportError | 库未安装或名称错误 | 检查库名，使用已安装的库 |
| NameError | 变量未定义 | 检查拼写，确保先定义再使用 |
| TypeError | 数据类型不匹配 | 检查数据类型，使用转换函数 |
| KeyError | DataFrame 列不存在 | 检查列名，用 df.columns 确认 |
| ValueError | 值不合法 | 检查数据范围和格式 |
| IndexError | 索引越界 | 检查数据长度和索引范围 |

### 步骤 3: 修正代码重新执行
1. 根据错误分析修正代码
2. 保留之前正确的部分，只修改出错的部分
3. 重新调用 execute_python 执行修正后的代码
4. 重复直到代码成功执行

### 调试示例
```
第一次执行 → KeyError: 'sales_amount'
→ 分析: 列名可能是 'amount' 而不是 'sales_amount'
→ 用 df.columns 确认实际列名
→ 修正代码重新执行 → 成功
```

## 输入
```json
{
  "code": "import pandas as pd\ndf = pd.read_sql('SELECT * FROM orders', engine)\nprint(df.describe())",
  "data_source_id": 1
}
```

## 与其他工具配合
- 先用 `execute_sql` 做探索性查询，了解数据结构
- Python 分析完成后用 `generate_chart` 生成 ECharts 可视化
- 复杂分析结果可以写入 `generate_report` 的章节
- 用 `generate_filter_widgets` 为 Python 生成的图表添加筛选
