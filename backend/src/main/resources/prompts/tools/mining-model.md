# 数据挖掘模型管理工具

你可以通过此工具管理用户的挖掘模型，支持查看、创建、修改参数、训练、验证、发布、下线和预测操作。还可以查看可用算法列表和创建自定义算法。

## 重要: 完整建模流程指南

当用户要求建模时，你应该引导他们走过完整的数据科学流程，而不是直接训练。推荐流程:

1. **探索数据** → `explore_data` 了解表结构、列类型、分布、缺失情况
2. **分析特征** → 基于数据探索结果，建议合适的特征列和目标列
3. **创建模型** → `create` 配置好特征、目标、算法
4. **验证数据** → `validate` 检查数据质量(缺失值、数据量、特征分布)
5. **训练** → `train` 执行训练
6. **评估** → 查看 `train` 返回的指标(accuracy, F1, precision, recall)，如果指标不好:
   - 建议调整超参数 (`update_params`)
   - 建议更换特征或算法
   - 重新训练
7. **样本外验证** → 如果数据量足够，使用 `update` 设置 validation_mode 为:
   - `cv`: 交叉验证(K-fold)，返回cv_mean和cv_std
   - `oos`: 样本外验证(训练+测试+交叉验证)
   - `temporal`: 时间外验证(按时间列排序分割前N%训练，后M%测试)
   然后重新 `train`
8. **发布** → 确认指标满意后 `publish`
9. **配置调度** → 通过 `update` 设置 predict_input_table, predict_input_filter, predict_result_table

### 指标评估标准
- 准确率 > 85% 为可用，> 95% 为优秀
- F1 分数与准确率差距大 → 可能类别不平衡，建议查看特征重要性
- 训练/测试指标差距大 → 可能过拟合，建议减少特征或增加正则化
- 如果数据量 < 100 行，提醒用户数据量偏少，模型可能不可靠

## 使用场景

### 探索数据
用户说「分析一下loan表」「帮我看看数据」「数据有什么特征」→ action: "explore_data", table_name
- 返回表结构、行数、列类型、数值统计、潜在目标列分布
- 基于结果自动建议特征和建模方向

### 验证模型数据
用户说「检查一下数据质量」「能不能训练」→ action: "validate", model_id
- 检查源表是否存在、列是否存在、数据量、目标列分布
- 返回是否可以训练以及潜在问题

### 查看模型
用户说「查看我的模型」「有哪些模型」→ action: "list"
用户说「XX模型的详细信息」→ action: "get", model_id
用户说「训练历史」「调参前后对比」「效果有没有改善」→ action: "history", model_id
- 返回历史训练记录，含超参数和指标变化
- 最近两次训练自动对比指标变化趋势

### 修改超参数 / 调参
用户说「把随机森林的树数量改成200」→ action: "update_params", model_id, hyperparameters: {"n_estimators": 200}
用户说「增加树的深度到10」→ action: "update_params", model_id, hyperparameters: {"max_depth": 10}
用户说「调参试试」「微调一下模型」→ 先 get 查看当前参数，再 update_params 修改

### 创建模型
用户说「帮我创建一个预测客户流失的模型」→ action: "create", name, algorithm, source_table, feature_columns, target_column
- 建议先 explore_data 了解数据，再选择特征

创建时可配置:
- `preprocessing`: 预处理配置 `{"handleMissing":"drop|fill_mean|fill_median","encoding":"label","scaling":"none|standard|minmax"}`
- `feature_transforms`: 特征变换列表 `[{"type":"log","columns":["amount"]},{"type":"binning","columns":["age"],"bins":5},{"type":"polynomial","columns":["income"],"degree":2}]`
  - `log`: 对数变换，适合右偏分布(金额、收入等)
  - `polynomial`: 多项式特征，适合非线性关系
  - `binning`: 分箱，适合连续变量离散化

### 训练模型
用户说「训练XX模型」→ action: "train", model_id
- 训练前建议先 validate
- 训练后查看返回的指标，给出评估意见

### 批量预测
用户说「批量预测」「跑一下预测」→ action: "batch_predict", model_id
- 需要模型已发布且配置了 predict_input_table
- 结果写入 predict_result_table

### 发布/下线
用户说「发布XX模型」→ action: "publish", model_id
用户说「发布并每天定时预测」→ action: "publish", model_id, schedule_enabled: true, schedule_cron: "*/1440", schedule_mode: "predict", input_table: "表名", result_table: "结果表名"
用户说「把XX模型下线」→ action: "offline", model_id

发布时可选配置:
- input_table: 批量预测的输入表
- result_table: 预测结果写入的表(不存在时自动创建)
- input_filter: 输入表筛选条件, 支持${etl_date}等变量
- schedule_enabled: 是否启用定时调度
- schedule_cron: 调度间隔(分钟数), 如 "*/60"=每小时, "*/1440"=每天
- schedule_mode: "train"=定期重训, "predict"=定期预测

### 预测
用户说「用XX模型预测这条数据」→ action: "predict", model_id, predict_input

### 查看可用算法
用户说「有哪些算法可用」「列出所有算法」→ action: "list_algorithms"

### 创建自定义算法
用户说「创建一个堆叠集成模型」「我需要一个自定义的加权投票分类器」
→ action: "create_algorithm"
  - algorithm_id: 英文标识符 (如 "stacking_rf_svm")
  - name: 中文名称
  - python_code_template: Python训练代码(必须创建clf对象)
  - model_types: 支持的模型类型数组

## 自定义算法Python代码要求

python_code_template 中可以使用的变量:
- `params`: 超参数字典
- `X`: 已编码/缩放的特征DataFrame
- `y`: 目标列Series (聚类时为None)
- `df`: 原始DataFrame

代码必须创建名为 `clf` 的模型对象。

## 注意事项
- hyperparameters 只需要传入要修改的字段
- 训练可能需要较长时间
- 发布前务必确认指标达标
- 创建自定义算法后立即可在流程编排中使用
