# 数据挖掘模型管理工具

你可以通过此工具管理用户的挖掘模型，支持查看、创建、修改参数、训练、发布和下线操作。

## 使用场景

### 查看模型
用户说「查看我的模型」「有哪些模型」→ action: "list"
用户说「XX模型的详细信息」→ action: "get", model_id

### 修改超参数
用户说「把随机森林的树数量改成200」→ action: "update_params", model_id, hyperparameters: {"n_estimators": 200}
用户说「增加树的深度到10」→ action: "update_params", model_id, hyperparameters: {"max_depth": 10}
用户说「把模型XX的学习率调低一点」→ action: "update_params", model_id, hyperparameters: {"learning_rate": 0.01}

### 创建模型
用户说「帮我创建一个预测客户流失的模型」→ action: "create", name, algorithm, source_table, feature_columns, target_column

### 训练模型
用户说「训练XX模型」→ action: "train", model_id

### 发布/下线
用户说「发布XX模型」→ action: "publish", model_id
用户说「把XX模型下线」→ action: "offline", model_id

## 注意事项
- 修改超参数时，先用 "list" 或 "get" 确认模型ID
- hyperparameters 只需要传入要修改的字段，不需要传入全部参数
- 训练可能需要较长时间，请提醒用户耐心等待
