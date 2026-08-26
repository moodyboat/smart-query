-- Built-in templates only construct estimators. All preprocessing is fitted by
-- the versioned sklearn Pipeline in the Python mining runtime.
UPDATE sq_algorithm
SET python_code_template = 'from sklearn.linear_model import LogisticRegression\nclf = LogisticRegression(**params)'
WHERE algorithm_id = 'logistic_regression'
  AND is_builtin = 1;

UPDATE sq_algorithm
SET python_code_template = 'from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor\nmodel_cls = RandomForestClassifier if _model_type == ''classification'' else RandomForestRegressor\nclf = model_cls(**params)'
WHERE algorithm_id = 'random_forest' AND is_builtin = 1;

UPDATE sq_algorithm
SET python_code_template = 'from xgboost import XGBClassifier, XGBRegressor\nmodel_cls = XGBClassifier if _model_type == ''classification'' else XGBRegressor\nclf = model_cls(**params)'
WHERE algorithm_id = 'xgboost' AND is_builtin = 1;

UPDATE sq_algorithm
SET python_code_template = 'from sklearn.tree import DecisionTreeClassifier, DecisionTreeRegressor\nmodel_cls = DecisionTreeClassifier if _model_type == ''classification'' else DecisionTreeRegressor\nclf = model_cls(**params)'
WHERE algorithm_id = 'decision_tree' AND is_builtin = 1;

UPDATE sq_algorithm
SET python_code_template = 'from sklearn.svm import SVC, SVR\nmodel_cls = SVC if _model_type == ''classification'' else SVR\nclf = model_cls(**params)'
WHERE algorithm_id = 'svm' AND is_builtin = 1;

UPDATE sq_algorithm
SET python_code_template = 'from sklearn.neighbors import KNeighborsClassifier, KNeighborsRegressor\nmodel_cls = KNeighborsClassifier if _model_type == ''classification'' else KNeighborsRegressor\nclf = model_cls(**params)'
WHERE algorithm_id = 'knn' AND is_builtin = 1;

UPDATE sq_algorithm
SET python_code_template = 'from sklearn.ensemble import GradientBoostingClassifier, GradientBoostingRegressor\nmodel_cls = GradientBoostingClassifier if _model_type == ''classification'' else GradientBoostingRegressor\nclf = model_cls(**params)'
WHERE algorithm_id = 'gradient_boosting' AND is_builtin = 1;

UPDATE sq_algorithm
SET python_code_template = 'from lightgbm import LGBMClassifier, LGBMRegressor\nmodel_cls = LGBMClassifier if _model_type == ''classification'' else LGBMRegressor\nclf = model_cls(**params)'
WHERE algorithm_id = 'lightgbm' AND is_builtin = 1;
