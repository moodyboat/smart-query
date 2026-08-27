-- Reclassify the built-in catalog by algorithm family and add stable sklearn templates.
UPDATE sq_algorithm SET category = '集成学习' WHERE algorithm_id IN ('random_forest','xgboost','gradient_boosting','lightgbm') AND is_builtin = 1;
UPDATE sq_algorithm SET category = '树模型' WHERE algorithm_id = 'decision_tree' AND is_builtin = 1;
UPDATE sq_algorithm SET category = '线性模型' WHERE algorithm_id = 'logistic_regression' AND is_builtin = 1;
UPDATE sq_algorithm SET category = '核方法' WHERE algorithm_id = 'svm' AND is_builtin = 1;
UPDATE sq_algorithm SET category = '近邻方法' WHERE algorithm_id = 'knn' AND is_builtin = 1;
UPDATE sq_algorithm SET category = '聚类' WHERE algorithm_id = 'kmeans' AND is_builtin = 1;

INSERT INTO sq_algorithm
  (algorithm_id, name, description, model_types, params_schema, python_code_template,
   is_builtin, icon, category, created_at, updated_at, deleted)
SELECT
  'neural_network', '多层感知机 (MLP)',
  '基于反向传播的全连接神经网络，支持分类和回归，可配置网络宽度、层数、激活函数、学习率与早停。',
  '["classification","regression"]',
  '[{"key":"hidden_layer_size","label":"每层神经元","type":"int","min":4,"max":1024,"step":4,"defaultValue":100},{"key":"hidden_layers","label":"隐藏层数","type":"int","min":1,"max":8,"step":1,"defaultValue":2},{"key":"activation","label":"激活函数","type":"select","options":["relu","tanh","logistic"],"defaultValue":"relu"},{"key":"solver","label":"优化器","type":"select","options":["adam","sgd","lbfgs"],"defaultValue":"adam"},{"key":"learning_rate_init","label":"初始学习率","type":"float","min":0.00001,"max":1,"step":0.0001,"defaultValue":0.001},{"key":"alpha","label":"L2正则系数","type":"float","min":0,"max":1,"step":0.0001,"defaultValue":0.0001},{"key":"max_iter","label":"最大迭代次数","type":"int","min":50,"max":5000,"step":50,"defaultValue":500},{"key":"early_stopping","label":"启用早停","type":"boolean","defaultValue":true}]',
  'from sklearn.neural_network import MLPClassifier, MLPRegressor\nhidden_size = int(params.pop(''hidden_layer_size'', 100))\nhidden_layers = int(params.pop(''hidden_layers'', 2))\nparams[''hidden_layer_sizes''] = tuple([hidden_size] * hidden_layers)\nparams.setdefault(''random_state'', 42)\nmodel_cls = MLPClassifier if _model_type == ''classification'' else MLPRegressor\nclf = model_cls(**params)',
  1, '🧠', '神经网络', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM sq_algorithm WHERE algorithm_id = 'neural_network');
