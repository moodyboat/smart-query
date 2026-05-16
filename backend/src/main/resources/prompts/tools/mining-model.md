# 数据挖掘模型管理工具

你可以通过此工具管理用户的挖掘模型，支持查看、创建、修改参数、训练、验证、发布、下线和预测操作。还可以查看可用算法列表和创建自定义算法。

## 重要: 完整建模流程指南

当用户要求建模时，你应该引导他们走过完整的数据科学流程，而不是直接训练。推荐流程:

1. **探索数据** → `explore_data` 了解表结构、列类型、分布、缺失情况
2. **分析特征** → 基于数据探索结果，建议合适的特征列和目标列
3. **创建模型** → `create` 配置好特征、目标、算法、预处理和验证模式
   - 时序数据务必设置 validation_mode="temporal" 并指定 temporal_column
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

### 过拟合检测（训练脚本自动计算）
训练结果会包含 train_accuracy/test_accuracy（分类）或 train_r2/test_r2（回归），以及 overfitting_gap：
- gap < 0.05: 泛化良好
- gap 0.05~0.15: 可接受，可优化
- gap > 0.15: **过拟合警告**，建议: (1) 减少特征数 (2) 增加正则化 (3) 增加训练数据 (4) 尝试更简单的模型
- 分类任务还会返回 confusion_matrix，可用于分析哪些类别预测较差

### 发布前检查清单
发布前确认以下条件满足:
1. ✅ 样本外/时间外验证通过 (validation_mode = cv/oos/temporal)
2. ✅ 过拟合检测 gap < 0.15
3. ✅ 核心指标达标 (分类 accuracy > 0.85, 回归 r2 > 0.7)
4. ✅ 已配置 predict_input_table 和 predict_result_table
5. ❌ 特征无数据泄漏（如目标列衍生物、未来数据）

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

### 一步重训（推荐调参方式）
用户说「把树数量改成200并重新训练」「换个特征再训练」「增加收入列再训练」
→ action: "retrain", model_id, 可同时传入 feature_columns/hyperparameters/validation_mode 等任意字段
- 一步完成: 更新配置 → 同步流程 → 重新训练 → 返回前后指标对比
- 自动显示新旧指标对比表格（箭头标注升降）

### 参数网格搜索
用户说「调优随机森林，试50/100/200棵树」「搜索最佳参数组合」
→ action: "tune", model_id, param_grid: {"n_estimators": [50, 100, 200], "max_depth": [5, 10]}
- 自动生成所有参数组合并并行训练
- 最多探索12种组合
- 返回对比表格，可从中选择最佳模型发布

### 同步模型与流程
用户说「同步模型和流程」「确保流程是最新的」
→ action: "sync_pipeline", model_id
- 强制双向同步模型配置与关联流程
- 模型字段变更 → 更新流程节点配置
- 流程节点变更 → 更新模型配置

### 创建模型
用户说「帮我创建一个预测客户流失的模型」→ action: "create", name, algorithm, source_table, feature_columns, target_column
- 建议先 explore_data 了解数据，再选择特征

创建时可配置:
- `preprocessing`: 预处理配置 `{"handleMissing":"drop|fill_mean|fill_median","encoding":"label","scaling":"none|standard|minmax"}`
- `feature_transforms`: 特征变换列表 `[{"type":"log","columns":["amount"]},{"type":"binning","columns":["age"],"bins":5},{"type":"polynomial","columns":["income"],"degree":2}]`
  - `log`: 对数变换，适合右偏分布(金额、收入等)
  - `polynomial`: 多项式特征，适合非线性关系，需指定 degree
  - `binning`: 分箱离散化，需指定 bins
  - `interaction`: 交互特征，自动生成列间乘积
  - `date_extract`: 日期提取(年/月/日/星期几)，适合日期列
  - `target_encode`: 目标编码，适合高基数分类特征(分类任务)
  - `frequency_encode`: 频率编码，将类别替换为其出现频率

### 训练模型
用户说「训练XX模型」→ action: "train", model_id
- 训练前建议先 validate
- 训练后查看返回的指标，给出评估意见

### 批量预测
用户说「批量预测」「跑一下预测」→ action: "batch_predict", model_id
- 可选参数 result_table: 指定输出表名，不传则用模型配置的 predict_result_table
- 需要模型已训练（status=trained 或 published）
- 结果自动写入指定表（无表时自动建表）

### 模型对比
用户说「对比随机森林和XGBoost」「哪种算法效果好」→ action: "compare", algorithms: ["random_forest", "xgboost"], source_table, feature_columns, target_column
- 并行创建并训练多个模型，返回指标对比表
- 支持 2~5 个算法同时对比
- 对比结果自动生成排名表格
- 训练完成后建议用户选择最佳模型发布

### 发布/下线
用户说「发布XX模型」→ action: "publish", model_id
用户说「发布并每天定时预测」→ action: "publish", model_id, schedule_enabled: true, schedule_cron: "0 6 * * *", schedule_mode: "predict", input_table: "表名", result_table: "结果表名"
用户说「把XX模型下线」→ action: "offline", model_id

发布时可选配置:
- input_table: 批量预测的输入表
- result_table: 预测结果写入的表(不存在时自动创建)
- input_filter: 输入表筛选条件, 支持 ${etl_date} 变量(自动替换为当天日期并加引号), 如 "application_date <= ${etl_date}"
- schedule_enabled: 是否启用定时调度
- schedule_cron: 标准5字段cron表达式, 如 "*/30 * * * *"=每30分钟, "0 6 * * *"=每天6:00, "0 8 * * 1"=每周一8:00, "0 0 1 * *"=每月1号0:00
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
