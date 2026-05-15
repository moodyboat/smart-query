-- 智能问数系统 - 算法注册表
-- V5__algorithm_registry.sql

CREATE TABLE IF NOT EXISTS sq_algorithm (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    algorithm_id VARCHAR(100) NOT NULL COMMENT '唯一标识符',
    name VARCHAR(200) NOT NULL COMMENT '显示名称',
    description TEXT COMMENT '算法描述',
    model_types JSON NOT NULL COMMENT '支持的模型类型 ["classification","regression"]',
    params_schema JSON NOT NULL COMMENT '参数定义数组',
    python_code_template TEXT NOT NULL COMMENT 'Python训练代码模板',
    is_builtin TINYINT NOT NULL DEFAULT 0 COMMENT '1=内置 0=自定义',
    icon VARCHAR(20) DEFAULT NULL COMMENT '图标emoji',
    category VARCHAR(50) DEFAULT NULL COMMENT '分类(用于面板分组)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE INDEX idx_algorithm_id (algorithm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 内置算法种子数据
INSERT INTO sq_algorithm (algorithm_id, name, description, model_types, params_schema, python_code_template, is_builtin, icon, category) VALUES
('random_forest', '随机森林', '集成学习方法，通过构建多棵决策树提高预测精度和稳定性',
 '["classification","regression"]',
 '[{"key":"n_estimators","label":"树的数量","type":"int","min":1,"max":1000,"step":1,"defaultValue":100,"hint":"n_estimators"},{"key":"max_depth","label":"最大深度","type":"int","min":1,"max":100,"step":1,"defaultValue":10,"hint":"max_depth"},{"key":"min_samples_split","label":"最小分裂样本数","type":"int","min":2,"max":100,"step":1,"defaultValue":2,"hint":"min_samples_split"},{"key":"min_samples_leaf","label":"叶节点最小样本数","type":"int","min":1,"max":50,"step":1,"defaultValue":1,"hint":"min_samples_leaf"}]',
 'from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor\nmodel_cls = RandomForestClassifier if y is not None and y.nunique() <= 20 else RandomForestRegressor\nclf = model_cls(**params)',
 1, '🌲', '分类/回归'),

('xgboost', 'XGBoost', '高效梯度提升算法，适合结构化数据的分类和回归任务',
 '["classification","regression"]',
 '[{"key":"n_estimators","label":"树的数量","type":"int","min":1,"max":1000,"step":1,"defaultValue":100,"hint":"n_estimators"},{"key":"max_depth","label":"最大深度","type":"int","min":1,"max":50,"step":1,"defaultValue":6,"hint":"max_depth"},{"key":"learning_rate","label":"学习率","type":"float","min":0.001,"max":1,"step":0.01,"defaultValue":0.3,"hint":"learning_rate"},{"key":"subsample","label":"子采样率","type":"float","min":0.1,"max":1,"step":0.1,"defaultValue":1,"hint":"subsample"}]',
 'from xgboost import XGBClassifier, XGBRegressor\nmodel_cls = XGBClassifier if y is not None and y.nunique() <= 20 else XGBRegressor\nclf = model_cls(**params)',
 1, '📈', '分类/回归'),

('decision_tree', '决策树', '基于特征进行递归分裂的树模型，可解释性强',
 '["classification","regression"]',
 '[{"key":"max_depth","label":"最大深度","type":"int","min":1,"max":100,"step":1,"defaultValue":10,"hint":"max_depth"},{"key":"min_samples_split","label":"最小分裂样本数","type":"int","min":2,"max":100,"step":1,"defaultValue":2,"hint":"min_samples_split"},{"key":"criterion","label":"分裂标准","type":"select","defaultValue":"gini","hint":"criterion","options":["gini","entropy"]}]',
 'from sklearn.tree import DecisionTreeClassifier, DecisionTreeRegressor\nmodel_cls = DecisionTreeClassifier if y is not None and y.nunique() <= 20 else DecisionTreeRegressor\nclf = model_cls(**params)',
 1, '🌳', '分类/回归'),

('logistic_regression', '逻辑回归', '线性分类模型，适合二分类和多分类任务',
 '["classification"]',
 '[{"key":"C","label":"正则化强度","type":"float","min":0.01,"max":100,"step":0.1,"defaultValue":1,"hint":"C"},{"key":"max_iter","label":"最大迭代次数","type":"int","min":10,"max":10000,"step":1,"defaultValue":100,"hint":"max_iter"},{"key":"solver","label":"求解器","type":"select","defaultValue":"lbfgs","hint":"solver","options":["lbfgs","liblinear","saga"]}]',
 'from sklearn.linear_model import LogisticRegression\nfrom sklearn.preprocessing import StandardScaler\nscaler = StandardScaler()\nX = pd.DataFrame(scaler.fit_transform(X), columns=X.columns)\nclf = LogisticRegression(**params)',
 1, '📐', '分类'),

('svm', '支持向量机', '通过寻找最优超平面进行分类，适合中小规模数据集',
 '["classification","regression"]',
 '[{"key":"C","label":"正则化强度","type":"float","min":0.01,"max":100,"step":0.1,"defaultValue":1,"hint":"C"},{"key":"kernel","label":"核函数","type":"select","defaultValue":"rbf","hint":"kernel","options":["rbf","linear","poly"]},{"key":"gamma","label":"Gamma","type":"select","defaultValue":"scale","hint":"gamma","options":["scale","auto"]}]',
 'from sklearn.svm import SVC, SVR\nmodel_cls = SVC if y is not None and y.nunique() <= 20 else SVR\nclf = model_cls(**params)',
 1, '🔶', '分类/回归'),

('knn', 'K近邻', '基于距离度量的惰性学习算法，简单直观',
 '["classification","regression"]',
 '[{"key":"n_neighbors","label":"邻居数 K","type":"int","min":1,"max":100,"step":1,"defaultValue":5,"hint":"n_neighbors"},{"key":"weights","label":"权重","type":"select","defaultValue":"uniform","hint":"weights","options":["uniform","distance"]}]',
 'from sklearn.neighbors import KNeighborsClassifier, KNeighborsRegressor\nmodel_cls = KNeighborsClassifier if y is not None and y.nunique() <= 20 else KNeighborsRegressor\nclf = model_cls(**params)',
 1, '🔗', '分类/回归'),

('gradient_boosting', '梯度提升', '串行构建弱学习器的集成方法，预测精度高',
 '["classification","regression"]',
 '[{"key":"n_estimators","label":"树的数量","type":"int","min":1,"max":1000,"step":1,"defaultValue":100,"hint":"n_estimators"},{"key":"max_depth","label":"最大深度","type":"int","min":1,"max":50,"step":1,"defaultValue":3,"hint":"max_depth"},{"key":"learning_rate","label":"学习率","type":"float","min":0.001,"max":1,"step":0.01,"defaultValue":0.1,"hint":"learning_rate"}]',
 'from sklearn.ensemble import GradientBoostingClassifier, GradientBoostingRegressor\nmodel_cls = GradientBoostingClassifier if y is not None and y.nunique() <= 20 else GradientBoostingRegressor\nclf = model_cls(**params)',
 1, '🚀', '分类/回归'),

('lightgbm', 'LightGBM', '微软开源的快速梯度提升框架，训练速度快、内存占用低',
 '["classification","regression"]',
 '[{"key":"n_estimators","label":"树的数量","type":"int","min":1,"max":1000,"step":1,"defaultValue":100,"hint":"n_estimators"},{"key":"max_depth","label":"最大深度","type":"int","min":1,"max":50,"step":1,"defaultValue":-1,"hint":"max_depth"},{"key":"learning_rate","label":"学习率","type":"float","min":0.001,"max":1,"step":0.01,"defaultValue":0.1,"hint":"learning_rate"},{"key":"num_leaves","label":"叶子节点数","type":"int","min":2,"max":256,"step":1,"defaultValue":31,"hint":"num_leaves"}]',
 'from lightgbm import LGBMClassifier, LGBMRegressor\nmodel_cls = LGBMClassifier if y is not None and y.nunique() <= 20 else LGBMRegressor\nclf = model_cls(**params)',
 1, '💡', '分类/回归'),

('kmeans', 'K-Means 聚类', '基于距离的无监督聚类算法，将数据分为K个簇',
 '["clustering"]',
 '[{"key":"n_clusters","label":"聚类数","type":"int","min":2,"max":50,"step":1,"defaultValue":3,"hint":"n_clusters"},{"key":"max_iter","label":"最大迭代次数","type":"int","min":10,"max":1000,"step":1,"defaultValue":300,"hint":"max_iter"}]',
 'from sklearn.cluster import KMeans\nparams.setdefault(''n_clusters'', 3)\nclf = KMeans(**params)',
 1, '🎯', '聚类');
