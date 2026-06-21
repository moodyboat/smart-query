
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `sq_algorithm`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_algorithm` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `algorithm_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '唯一标识符',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '显示名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '算法描述',
  `model_types` json NOT NULL COMMENT '支持的模型类型 ["classification","regression"]',
  `params_schema` json NOT NULL COMMENT '参数定义数组',
  `python_code_template` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Python训练代码模板',
  `is_builtin` tinyint NOT NULL DEFAULT '0' COMMENT '1=内置 0=自定义',
  `icon` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图标emoji',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类(用于面板分组)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_algorithm_id` (`algorithm_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_algorithm` WRITE;
/*!40000 ALTER TABLE `sq_algorithm` DISABLE KEYS */;
INSERT INTO `sq_algorithm` VALUES (1,'random_forest','随机森林','集成学习方法，通过构建多棵决策树提高预测精度和稳定性','[\"classification\", \"regression\"]','[{\"key\": \"n_estimators\", \"max\": 1000, \"min\": 1, \"hint\": \"n_estimators\", \"step\": 1, \"type\": \"int\", \"label\": \"树的数量\", \"defaultValue\": 100}, {\"key\": \"max_depth\", \"max\": 100, \"min\": 1, \"hint\": \"max_depth\", \"step\": 1, \"type\": \"int\", \"label\": \"最大深度\", \"defaultValue\": 10}, {\"key\": \"min_samples_split\", \"max\": 100, \"min\": 2, \"hint\": \"min_samples_split\", \"step\": 1, \"type\": \"int\", \"label\": \"最小分裂样本数\", \"defaultValue\": 2}, {\"key\": \"min_samples_leaf\", \"max\": 50, \"min\": 1, \"hint\": \"min_samples_leaf\", \"step\": 1, \"type\": \"int\", \"label\": \"叶节点最小样本数\", \"defaultValue\": 1}]','from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor\nmodel_cls = RandomForestClassifier if y is not None and y.nunique() <= 20 else RandomForestRegressor\nclf = model_cls(**params)',1,'🌲','分类/回归','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(2,'xgboost','XGBoost','高效梯度提升算法，适合结构化数据的分类和回归任务','[\"classification\", \"regression\"]','[{\"key\": \"n_estimators\", \"max\": 1000, \"min\": 1, \"hint\": \"n_estimators\", \"step\": 1, \"type\": \"int\", \"label\": \"树的数量\", \"defaultValue\": 100}, {\"key\": \"max_depth\", \"max\": 50, \"min\": 1, \"hint\": \"max_depth\", \"step\": 1, \"type\": \"int\", \"label\": \"最大深度\", \"defaultValue\": 6}, {\"key\": \"learning_rate\", \"max\": 1, \"min\": 0.001, \"hint\": \"learning_rate\", \"step\": 0.01, \"type\": \"float\", \"label\": \"学习率\", \"defaultValue\": 0.3}, {\"key\": \"subsample\", \"max\": 1, \"min\": 0.1, \"hint\": \"subsample\", \"step\": 0.1, \"type\": \"float\", \"label\": \"子采样率\", \"defaultValue\": 1}]','from xgboost import XGBClassifier, XGBRegressor\nmodel_cls = XGBClassifier if y is not None and y.nunique() <= 20 else XGBRegressor\nclf = model_cls(**params)',1,'📈','分类/回归','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(3,'decision_tree','决策树','基于特征进行递归分裂的树模型，可解释性强','[\"classification\", \"regression\"]','[{\"key\": \"max_depth\", \"max\": 100, \"min\": 1, \"hint\": \"max_depth\", \"step\": 1, \"type\": \"int\", \"label\": \"最大深度\", \"defaultValue\": 10}, {\"key\": \"min_samples_split\", \"max\": 100, \"min\": 2, \"hint\": \"min_samples_split\", \"step\": 1, \"type\": \"int\", \"label\": \"最小分裂样本数\", \"defaultValue\": 2}, {\"key\": \"criterion\", \"hint\": \"criterion\", \"type\": \"select\", \"label\": \"分裂标准\", \"options\": [\"gini\", \"entropy\"], \"defaultValue\": \"gini\"}]','from sklearn.tree import DecisionTreeClassifier, DecisionTreeRegressor\nmodel_cls = DecisionTreeClassifier if y is not None and y.nunique() <= 20 else DecisionTreeRegressor\nclf = model_cls(**params)',1,'🌳','分类/回归','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(4,'logistic_regression','逻辑回归','线性分类模型，适合二分类和多分类任务','[\"classification\"]','[{\"key\": \"C\", \"max\": 100, \"min\": 0.01, \"hint\": \"C\", \"step\": 0.1, \"type\": \"float\", \"label\": \"正则化强度\", \"defaultValue\": 1}, {\"key\": \"max_iter\", \"max\": 10000, \"min\": 10, \"hint\": \"max_iter\", \"step\": 1, \"type\": \"int\", \"label\": \"最大迭代次数\", \"defaultValue\": 100}, {\"key\": \"solver\", \"hint\": \"solver\", \"type\": \"select\", \"label\": \"求解器\", \"options\": [\"lbfgs\", \"liblinear\", \"saga\"], \"defaultValue\": \"lbfgs\"}]','from sklearn.linear_model import LogisticRegression\nfrom sklearn.preprocessing import StandardScaler\nscaler = StandardScaler()\nX = pd.DataFrame(scaler.fit_transform(X), columns=X.columns)\nclf = LogisticRegression(**params)',1,'📐','分类','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(5,'svm','支持向量机','通过寻找最优超平面进行分类，适合中小规模数据集','[\"classification\", \"regression\"]','[{\"key\": \"C\", \"max\": 100, \"min\": 0.01, \"hint\": \"C\", \"step\": 0.1, \"type\": \"float\", \"label\": \"正则化强度\", \"defaultValue\": 1}, {\"key\": \"kernel\", \"hint\": \"kernel\", \"type\": \"select\", \"label\": \"核函数\", \"options\": [\"rbf\", \"linear\", \"poly\"], \"defaultValue\": \"rbf\"}, {\"key\": \"gamma\", \"hint\": \"gamma\", \"type\": \"select\", \"label\": \"Gamma\", \"options\": [\"scale\", \"auto\"], \"defaultValue\": \"scale\"}]','from sklearn.svm import SVC, SVR\nmodel_cls = SVC if y is not None and y.nunique() <= 20 else SVR\nclf = model_cls(**params)',1,'🔶','分类/回归','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(6,'knn','K近邻','基于距离度量的惰性学习算法，简单直观','[\"classification\", \"regression\"]','[{\"key\": \"n_neighbors\", \"max\": 100, \"min\": 1, \"hint\": \"n_neighbors\", \"step\": 1, \"type\": \"int\", \"label\": \"邻居数 K\", \"defaultValue\": 5}, {\"key\": \"weights\", \"hint\": \"weights\", \"type\": \"select\", \"label\": \"权重\", \"options\": [\"uniform\", \"distance\"], \"defaultValue\": \"uniform\"}]','from sklearn.neighbors import KNeighborsClassifier, KNeighborsRegressor\nmodel_cls = KNeighborsClassifier if y is not None and y.nunique() <= 20 else KNeighborsRegressor\nclf = model_cls(**params)',1,'🔗','分类/回归','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(7,'gradient_boosting','梯度提升','串行构建弱学习器的集成方法，预测精度高','[\"classification\", \"regression\"]','[{\"key\": \"n_estimators\", \"max\": 1000, \"min\": 1, \"hint\": \"n_estimators\", \"step\": 1, \"type\": \"int\", \"label\": \"树的数量\", \"defaultValue\": 100}, {\"key\": \"max_depth\", \"max\": 50, \"min\": 1, \"hint\": \"max_depth\", \"step\": 1, \"type\": \"int\", \"label\": \"最大深度\", \"defaultValue\": 3}, {\"key\": \"learning_rate\", \"max\": 1, \"min\": 0.001, \"hint\": \"learning_rate\", \"step\": 0.01, \"type\": \"float\", \"label\": \"学习率\", \"defaultValue\": 0.1}]','from sklearn.ensemble import GradientBoostingClassifier, GradientBoostingRegressor\nmodel_cls = GradientBoostingClassifier if y is not None and y.nunique() <= 20 else GradientBoostingRegressor\nclf = model_cls(**params)',1,'🚀','分类/回归','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(8,'lightgbm','LightGBM','微软开源的快速梯度提升框架，训练速度快、内存占用低','[\"classification\", \"regression\"]','[{\"key\": \"n_estimators\", \"max\": 1000, \"min\": 1, \"hint\": \"n_estimators\", \"step\": 1, \"type\": \"int\", \"label\": \"树的数量\", \"defaultValue\": 100}, {\"key\": \"max_depth\", \"max\": 50, \"min\": 1, \"hint\": \"max_depth\", \"step\": 1, \"type\": \"int\", \"label\": \"最大深度\", \"defaultValue\": -1}, {\"key\": \"learning_rate\", \"max\": 1, \"min\": 0.001, \"hint\": \"learning_rate\", \"step\": 0.01, \"type\": \"float\", \"label\": \"学习率\", \"defaultValue\": 0.1}, {\"key\": \"num_leaves\", \"max\": 256, \"min\": 2, \"hint\": \"num_leaves\", \"step\": 1, \"type\": \"int\", \"label\": \"叶子节点数\", \"defaultValue\": 31}]','from lightgbm import LGBMClassifier, LGBMRegressor\nmodel_cls = LGBMClassifier if y is not None and y.nunique() <= 20 else LGBMRegressor\nclf = model_cls(**params)',1,'💡','分类/回归','2026-06-19 10:32:37','2026-06-19 10:32:37',0),(9,'kmeans','K-Means 聚类','基于距离的无监督聚类算法，将数据分为K个簇','[\"clustering\"]','[{\"key\": \"n_clusters\", \"max\": 50, \"min\": 2, \"hint\": \"n_clusters\", \"step\": 1, \"type\": \"int\", \"label\": \"聚类数\", \"defaultValue\": 3}, {\"key\": \"max_iter\", \"max\": 1000, \"min\": 10, \"hint\": \"max_iter\", \"step\": 1, \"type\": \"int\", \"label\": \"最大迭代次数\", \"defaultValue\": 300}]','from sklearn.cluster import KMeans\nparams.setdefault(\'n_clusters\', 3)\nclf = KMeans(**params)',1,'🎯','聚类','2026-06-19 10:32:37','2026-06-19 10:32:37',0);
/*!40000 ALTER TABLE `sq_algorithm` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_chart`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_chart` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `message_id` bigint DEFAULT NULL,
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `chart_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'bar/line/pie/scatter/heatmap/map/etc',
  `echarts_option` json NOT NULL,
  `image_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图表图片文件路径',
  `data_source_id` bigint DEFAULT NULL,
  `base_sql` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '关联的SQL(支持筛选控件联动)',
  `filter_bindings` json DEFAULT NULL COMMENT '筛选控件绑定关系',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_image_path` (`image_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_chart` WRITE;
/*!40000 ALTER TABLE `sq_chart` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_chart` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_chat_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `role` enum('user','assistant','system','tool') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `tool_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具名称(tool角色时)',
  `tool_call_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具调用ID',
  `token_count` int DEFAULT '0',
  `model` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用的模型',
  `trace_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '全链路追踪ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `metadata` json DEFAULT NULL COMMENT '结构化工具块(JSON)',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_chat_message_trace_id` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_chat_message` WRITE;
/*!40000 ALTER TABLE `sq_chat_message` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_chat_message` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `data_source_id` bigint DEFAULT NULL,
  `user_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'default',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-活跃 0-归档',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_conversation` WRITE;
/*!40000 ALTER TABLE `sq_conversation` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_conversation` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_dashboard`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_dashboard` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `message_id` bigint DEFAULT NULL,
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `layout` json NOT NULL COMMENT '仪表盘布局配置',
  `chart_ids` json NOT NULL COMMENT '包含的图表ID列表',
  `filter_widgets` json DEFAULT NULL COMMENT '全局筛选控件',
  `data_source_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_dashboard` WRITE;
/*!40000 ALTER TABLE `sq_dashboard` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_dashboard` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_data_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_data_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_source_id` bigint NOT NULL,
  `table_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `table_comment` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '',
  `column_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `column_comment` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '',
  `column_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '',
  `is_dimension` tinyint DEFAULT '0' COMMENT '1-维度 0-指标',
  `sample_values` json DEFAULT NULL COMMENT '采样值',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_datasource_table` (`data_source_id`,`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_data_dict` WRITE;
/*!40000 ALTER TABLE `sq_data_dict` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_data_dict` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_data_source`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_data_source` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` enum('mysql','postgresql','gbase','oracle','dm') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'mysql',
  `host` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `port` int NOT NULL,
  `database_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `username` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `extra_config` json DEFAULT NULL COMMENT '额外JDBC参数',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-正常 0-禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `for_question_answering` tinyint(1) DEFAULT '1' COMMENT '是否可用于问答功能: 1=可用, 0=不可用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_data_source` WRITE;
/*!40000 ALTER TABLE `sq_data_source` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_data_source` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_llm_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_llm_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `model_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `api_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `api_key` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `max_tokens` int NOT NULL DEFAULT '4096',
  `temperature` decimal(3,2) NOT NULL DEFAULT '0.10',
  `is_default` tinyint NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-启用 0-禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `model_code` (`model_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_llm_config` WRITE;
/*!40000 ALTER TABLE `sq_llm_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_llm_config` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_metadata_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_metadata_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_source_id` bigint DEFAULT NULL COMMENT '数据源ID',
  `table_name` varchar(100) NOT NULL COMMENT '表名',
  `column_name` varchar(100) DEFAULT NULL COMMENT '字段名',
  `config_type` varchar(20) NOT NULL COMMENT '配置类型：table/column/business_term',
  `name` varchar(200) DEFAULT NULL COMMENT '名称/标题',
  `description` text COMMENT '描述/注释',
  `business_term` varchar(100) DEFAULT NULL COMMENT '业务术语',
  `aliases` json DEFAULT NULL COMMENT '别名列表 ["alias1","alias2"]',
  `data_type` varchar(50) DEFAULT NULL COMMENT '数据类型',
  `is_sensitive` tinyint DEFAULT '0' COMMENT '是否敏感字段',
  `is_filterable` tinyint DEFAULT '1' COMMENT '是否可作为筛选条件',
  `is_dimension` tinyint DEFAULT '0' COMMENT '是否维度',
  `is_metric` tinyint DEFAULT '0' COMMENT '是否指标',
  `unit` varchar(50) DEFAULT NULL COMMENT '单位',
  `format` varchar(100) DEFAULT NULL COMMENT '格式化',
  `dictionary` json DEFAULT NULL COMMENT '字典映射 {"value1":"label1","value2":"label2"}',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_datasource_table_column` (`data_source_id`,`table_name`,`column_name`),
  KEY `idx_data_source` (`data_source_id`),
  KEY `idx_table` (`table_name`),
  KEY `idx_config_type` (`config_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='元数据配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_metadata_config` WRITE;
/*!40000 ALTER TABLE `sq_metadata_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_metadata_config` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_mining_model`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_mining_model` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pipeline_id` bigint DEFAULT NULL COMMENT '关联流水线',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `data_source_id` bigint NOT NULL,
  `conversation_id` bigint DEFAULT NULL COMMENT '来源对话ID',
  `model_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'classification/regression/clustering/anomaly_detection',
  `algorithm` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'random_forest/xgboost/kmeans/...',
  `source_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '源数据表',
  `feature_columns` json DEFAULT NULL COMMENT '特征列',
  `target_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标列',
  `preprocessing` json DEFAULT NULL COMMENT '预处理配置',
  `hyperparameters` json NOT NULL COMMENT '算法超参数 {n_estimators:100, max_depth:5, ...}',
  `metrics` json DEFAULT NULL COMMENT '评估指标 {accuracy:0.95, f1:0.93, ...}',
  `feature_importance` json DEFAULT NULL COMMENT '特征重要性',
  `training_log` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '训练日志',
  `model_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '持久化模型路径(.pkl)',
  `status` enum('draft','training','trained','published','offline','failed') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'draft',
  `version` int NOT NULL DEFAULT '1',
  `schedule_cron` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '调度cron表达式',
  `schedule_enabled` tinyint NOT NULL DEFAULT '0',
  `last_run_at` datetime DEFAULT NULL,
  `next_run_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'manual',
  `predict_input_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '批量预测：输入数据表',
  `predict_result_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '批量预测：结果保存表',
  `schedule_mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'train' COMMENT '调度模式: train=定期重训, predict=定期预测',
  `predict_input_filter` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '批量预测输入筛选条件，支持变量: etl_date/today/yesterday/today-N',
  `validation_mode` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'train_test' COMMENT 'train_test / cv / oos / temporal',
  `cv_folds` int DEFAULT '5' COMMENT '交叉验证折数',
  `test_size` double DEFAULT '0.2' COMMENT '测试集比例',
  `temporal_column` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '时间列(用于时序验证)',
  `validation_metrics` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '验证指标JSON(样本外/时序验证结果)',
  `last_synced_at` datetime DEFAULT NULL COMMENT 'Last pipeline-model sync timestamp',
  PRIMARY KEY (`id`),
  KEY `idx_pipeline` (`pipeline_id`),
  KEY `idx_datasource` (`data_source_id`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_mining_model` WRITE;
/*!40000 ALTER TABLE `sq_mining_model` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_mining_model` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_mining_pipeline`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_mining_pipeline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `data_source_id` bigint NOT NULL,
  `conversation_id` bigint DEFAULT NULL COMMENT '来源对话ID',
  `status` enum('draft','ready','running','completed','failed') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'draft',
  `nodes` json NOT NULL COMMENT '流程节点定义 [{id,type,config,position}]',
  `edges` json DEFAULT NULL COMMENT '节点连线 [{source,target}]',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `last_executed_at` datetime DEFAULT NULL COMMENT '上次执行时间',
  `execution_log` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '执行日志',
  `source_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'manual' COMMENT 'manual/chat/auto',
  `last_synced_at` datetime DEFAULT NULL COMMENT 'Last model-pipeline sync timestamp',
  PRIMARY KEY (`id`),
  KEY `idx_datasource` (`data_source_id`),
  KEY `idx_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_mining_pipeline` WRITE;
/*!40000 ALTER TABLE `sq_mining_pipeline` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_mining_pipeline` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_model_execution`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_model_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_id` bigint NOT NULL,
  `trigger_type` enum('manual','schedule','chat') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'manual',
  `status` enum('pending','running','success','failed') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending',
  `hyperparameters` json DEFAULT NULL COMMENT '本次执行使用的超参数快照',
  `metrics` json DEFAULT NULL COMMENT '本次执行结果指标',
  `execution_log` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `execution_time_ms` int DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_model` (`model_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_model_execution` WRITE;
/*!40000 ALTER TABLE `sq_model_execution` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_model_execution` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_ontology_dimension`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_ontology_dimension` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_source_id` bigint NOT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '维度名: 如 "地区"',
  `business_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务名: 如 "销售区域"',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `source_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源表',
  `source_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源字段',
  `dimension_type` enum('categorical','temporal','numeric_range','hierarchical') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'categorical',
  `parent_dimension_id` bigint DEFAULT NULL COMMENT '父维度ID (用于层次结构如 省→市→区)',
  `hierarchy_level` int DEFAULT '0' COMMENT '层次级别: 0=顶级',
  `hierarchy_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '层次路径: "地区/华东/上海"',
  `rollup_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上卷字段: 如 city -> province 的映射字段',
  `date_format` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '日期格式: YYYY-MM-DD',
  `fiscal_year_start` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '01-01' COMMENT '财年起始月日',
  `sort_order` int DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_datasource` (`data_source_id`),
  KEY `idx_parent` (`parent_dimension_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_ontology_dimension` WRITE;
/*!40000 ALTER TABLE `sq_ontology_dimension` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_ontology_dimension` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_ontology_glossary`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_ontology_glossary` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_source_id` bigint NOT NULL,
  `term` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '术语: 如 "毛利率"',
  `synonyms` json DEFAULT NULL COMMENT '同义词列表: ["毛利占比","gross_margin"]',
  `definition` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务定义',
  `mapped_metric_id` bigint DEFAULT NULL COMMENT '关联的指标ID',
  `mapped_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联表',
  `mapped_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联字段',
  `mapping_rule` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '映射规则(自然语言描述)',
  `usage_examples` json DEFAULT NULL COMMENT '使用示例: ["上个月毛利率是多少?"]',
  `category` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类: 财务/销售/库存/人力',
  `sort_order` int DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_datasource` (`data_source_id`),
  FULLTEXT KEY `idx_term` (`term`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_ontology_glossary` WRITE;
/*!40000 ALTER TABLE `sq_ontology_glossary` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_ontology_glossary` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_ontology_indicator_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_ontology_indicator_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_source_id` bigint NOT NULL,
  `config_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置名',
  `indicator_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '指标定义表名',
  `name_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '指标名列',
  `formula_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '公式列',
  `category_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类列',
  `unit_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位列',
  `description_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述列',
  `detail_table_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '明细表名列 (指标定义表中的字段,指向明细数据表)',
  `detail_filter_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '明细表筛选条件列',
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_datasource` (`data_source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_ontology_indicator_config` WRITE;
/*!40000 ALTER TABLE `sq_ontology_indicator_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_ontology_indicator_config` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_ontology_metric`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_ontology_metric` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_source_id` bigint NOT NULL,
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '指标名称: 如 "月销售额"',
  `business_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务名称: 如 "月度销售总额"',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '业务含义描述',
  `metric_type` enum('basic','derived','composite') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'basic' COMMENT 'basic=基础指标(直接取字段), derived=派生指标(计算公式), composite=复合指标(多指标组合)',
  `source_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源表',
  `source_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源字段',
  `aggregation` enum('sum','count','avg','max','min','count_distinct','none') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '聚合方式',
  `formula` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '计算公式: 如 "SUM(amount) / COUNT(DISTINCT customer_id)"',
  `formula_sql_template` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'SQL模板: 含 {metric.xxx} 占位符引用其他指标',
  `dimensions` json DEFAULT NULL COMMENT '适用维度列表 ["region","product_category","time_month"]',
  `default_grain` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '默认粒度: day/week/month/quarter/year',
  `time_column` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '时间维度字段',
  `filter_condition` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '默认筛选: 如 "status = ''completed''"',
  `unit` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '单位: 元/个/人/%',
  `format_pattern` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '格式化: "#,##0.00"',
  `sort_order` int DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-启用 0-禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_datasource` (`data_source_id`),
  KEY `idx_source_table` (`source_table`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_ontology_metric` WRITE;
/*!40000 ALTER TABLE `sq_ontology_metric` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_ontology_metric` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_ontology_metric_dimension`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_ontology_metric_dimension` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `metric_id` bigint NOT NULL,
  `dimension_id` bigint NOT NULL,
  `relationship_type` enum('dimension_of','filter_by','drill_down') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'dimension_of',
  `join_condition` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '关联条件: 当指标和维度不在同一表时',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_metric_dim` (`metric_id`,`dimension_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_ontology_metric_dimension` WRITE;
/*!40000 ALTER TABLE `sq_ontology_metric_dimension` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_ontology_metric_dimension` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_prediction_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_prediction_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_id` bigint NOT NULL COMMENT '关联模型ID',
  `model_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型名称(冗余)',
  `batch_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '批量预测批次ID',
  `input_data` json DEFAULT NULL COMMENT '输入特征值',
  `prediction` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '预测结果',
  `probability` double DEFAULT NULL COMMENT '预测概率/置信度',
  `result_table` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '结果写入的业务表名',
  `predicted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预测时间',
  PRIMARY KEY (`id`),
  KEY `idx_model` (`model_id`),
  KEY `idx_batch` (`batch_id`),
  KEY `idx_predicted` (`predicted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_prediction_result` WRITE;
/*!40000 ALTER TABLE `sq_prediction_result` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_prediction_result` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_prompt_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_prompt_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scenario_id` bigint NOT NULL COMMENT '场景ID',
  `name` varchar(100) NOT NULL COMMENT '模板名称',
  `code` varchar(50) NOT NULL COMMENT '模板编码',
  `description` text COMMENT '模板描述',
  `type` varchar(20) NOT NULL COMMENT '模板类型：system/user/assistant',
  `content` text NOT NULL COMMENT '提示词内容',
  `variables` json DEFAULT NULL COMMENT '变量配置 [{"name":"variable_name","type":"string","default_value":"","description":"描述"}]',
  `model_config` json DEFAULT NULL COMMENT '模型配置 {"model":"glm-5.1","temperature":0.7,"max_tokens":2000}',
  `is_default` tinyint DEFAULT '0' COMMENT '是否为该场景默认模板',
  `is_system` tinyint DEFAULT '0' COMMENT '是否系统预设',
  `is_enabled` tinyint DEFAULT '1' COMMENT '是否启用',
  `version` varchar(20) DEFAULT '1.0' COMMENT '版本号',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_prompt_scenario` (`scenario_id`),
  KEY `idx_prompt_type` (`type`),
  KEY `idx_prompt_default` (`is_default`),
  CONSTRAINT `sq_prompt_template_ibfk_1` FOREIGN KEY (`scenario_id`) REFERENCES `sq_scenario` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提示词模板表';
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_prompt_template` WRITE;
/*!40000 ALTER TABLE `sq_prompt_template` DISABLE KEYS */;
INSERT INTO `sq_prompt_template` VALUES (1,1,'通用系统提示','general_system','通用查询场景的系统提示词','system','你是一个专业的数据分析师助手，帮助用户通过自然语言查询数据库并进行数据分析。\n\n## 核心能力\n1. 理解用户的自然语言查询意图\n2. 生成准确的SQL查询语句\n3. 分析查询结果并提供洞察\n4. 生成合适的图表可视化\n5. 回答数据相关问题\n\n## 数据库信息\n{{database_schema}}\n\n## 查询规则\n1. 仅允许执行 SELECT、SHOW、DESCRIBE、EXPLAIN 查询\n2. 表名和字段名使用反引号包裹\n3. 优先使用 WHERE 条件过滤数据，避免全表扫描\n4. 注意处理 NULL 值\n5. 对于聚合查询，使用有意义的字段别名\n\n## 回答规范\n1. 先确认理解用户的问题\n2. 说明执行的SQL逻辑\n3. 展示查询结果\n4. 提供数据洞察和建议\n5. 必要时推荐后续分析方向','[{\"name\": \"database_schema\", \"type\": \"string\", \"description\": \"数据库schema信息\", \"default_value\": \"\"}]',NULL,1,1,1,'1.0','2026-06-19 02:32:37','2026-06-19 02:32:37',0),(2,2,'销售分析系统提示','sales_system','销售分析专用系统提示词','system','你是一个销售数据分析专家，专注于帮助企业洞察销售趋势、识别机会和风险。\n\n## 核心能力\n1. 销售趋势分析（同比、环比、移动平均）\n2. 商品销售排行和ABC分析\n3. 客户购买行为分析\n4. 区域销售对比分析\n5. 销售预测和目标达成分析\n\n## 关键指标\n- 销售额、销量、客单价\n- 毛利率、利润率\n- 复购率、转化率\n- 同比增长率、环比增长率\n\n## 分析维度\n- 时间维度：日、周、月、季、年\n- 地区维度：大区、省份、城市\n- 商品维度：品类、品牌、SKU\n- 客户维度：新老客户、客户等级\n\n## 专业建议\n1. 识别畅销品和滞销品\n2. 发现销售异常和波动原因\n3. 提出优化商品结构的建议\n4. 预测未来销售趋势','[]',NULL,1,1,1,'1.0','2026-06-19 02:32:37','2026-06-19 02:32:37',0),(3,3,'用户分析系统提示','user_system','用户行为分析专用系统提示词','system','你是一个用户分析专家，专注于用户行为洞察、用户画像和用户增长。\n\n## 核心能力\n1. 用户画像分析（基础属性、行为特征、偏好标签）\n2. 用户生命周期分析（新增、活跃、留存、流失）\n3. 用户行为路径分析（漏斗、转化、路径）\n4. 用户分群和精细化运营\n5. 用户价值分析（RFM模型、CLV）\n\n## 关键指标\n- DAU、MAU、新增用户、流失用户\n- 次日留存、7日留存、30日留存\n- 用户生命周期价值（LTV/CLV）\n- 用户获取成本（CAC）\n- 活跃度、参与度、NPS得分\n\n## 分析框架\n1. AARRR模型（获取、激活、留存、变现、推荐）\n2. RFM模型（最近购买、频率、金额）\n3. 用户分层（新用户、活跃用户、沉默用户、流失用户）\n4. 行为漏斗分析\n\n## 分析输出\n1. 用户画像总结\n2. 关键发现和洞察\n3. 问题诊断和原因分析\n4. 行动建议和优化方案','[]',NULL,1,1,1,'1.0','2026-06-19 02:32:37','2026-06-19 02:32:37',0),(4,4,'财务分析系统提示','financial_system','财务数据分析专用系统提示词','system','你是一个财务分析专家，专注于企业财务数据分析和经营决策支持。\n\n## 核心能力\n1. 财务报表分析（资产负债表、利润表、现金流量表）\n2. 财务比率分析（盈利能力、偿债能力、运营能力、成长能力）\n3. 成本费用分析\n4. 预算执行分析\n5. 财务风险预警\n\n## 关键指标\n- 营业收入、净利润、毛利率、净利率\n- 资产负债率、流动比率、速动比率\n- 应收账款周转率、存货周转率\n- 经营现金流、自由现金流\n- ROE、ROA\n\n## 分析维度\n- 时间维度：同比、环比、预算差异\n- 部门维度：各成本中心、利润中心\n- 项目维度：重点项目投入产出\n- 产品维度：产品线盈利能力\n\n## 分析原则\n1. 数据准确性优先\n2. 关注趋势变化和异常波动\n3. 横向对比和纵向对比结合\n4. 定量分析和定性分析结合\n5. 提供决策建议和风险提示','[]',NULL,1,1,1,'1.0','2026-06-19 02:32:37','2026-06-19 02:32:37',0),(5,5,'运营监控系统提示','ops_system','运营指标监控和预警专用系统提示词','system','你是一个运营监控专家，专注于实时监控业务指标、发现异常和及时预警。\n\n## 核心能力\n1. 实时指标监控（DAU、订单量、GMV等）\n2. 异常检测和预警\n3. 业务漏斗监控\n4. 系统健康度监控\n5. 自动化巡检报告\n\n## 监控指标类型\n1. 核心业务指标（DAU、订单量、GMV、转化率）\n2. 性能指标（响应时间、成功率、QPS）\n3. 质量指标（错误率、客诉率、退货率）\n4. 资源指标（CPU、内存、磁盘、网络）\n\n## 异常检测\n1. 突增突降检测\n2. 趋势偏离检测\n3. 周期性异常检测\n4. 阈值超限预警\n\n## 预警级别\n- P0：严重影响，立即处理\n- P1：重要影响，尽快处理\n- P2：一般影响，关注处理\n- P3：轻微影响，计划处理\n\n## 报告输出\n1. 当前状态摘要\n2. 异常事件列表\n3. 趋势分析\n4. 根因分析\n5. 处置建议','[]',NULL,1,1,1,'1.0','2026-06-19 02:32:37','2026-06-19 02:32:37',0),(6,6,'数据挖掘系统提示','mining_system','机器学习和数据挖掘专用系统提示词','system','你是一个数据挖掘和机器学习专家，能够构建预测模型、发现数据规律。\n\n## 核心能力\n1. 数据探索和特征工程\n2. 分类、回归、聚类算法应用\n3. 模型训练和评估\n4. 模型解释和可视化\n5. 预测和推理\n\n## 算法能力\n1. 分类：逻辑回归、决策树、随机森林、XGBoost\n2. 回归：线性回归、岭回归、Lasso\n3. 聚类：K-Means、DBSCAN\n4. 时序：ARIMA、Prophet\n5. 降维：PCA、t-SNE\n\n## 工作流程\n1. 理解业务问题和目标\n2. 数据探索和理解\n3. 特征选择和工程\n4. 算法选择和调参\n5. 模型训练和评估\n6. 结果解释和建议\n\n## 评估指标\n- 分类：准确率、精确率、召回率、F1、AUC\n- 回归：MSE、MAE、R²\n- 聚类：轮廓系数、Davies-Bouldin指数\n\n## 输出规范\n1. 问题定义和目标\n2. 数据摘要\n3. 特征说明\n4. 模型选择和理由\n5. 评估结果\n6. 模型解释\n7. 业务建议','[]',NULL,1,1,1,'1.0','2026-06-19 02:32:37','2026-06-19 02:32:37',0);
/*!40000 ALTER TABLE `sq_prompt_template` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_python_execution`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_python_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `message_id` bigint DEFAULT NULL,
  `code` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `stdout` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `stderr` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `exit_code` int DEFAULT NULL,
  `execution_time_ms` int DEFAULT NULL,
  `status` enum('pending','running','success','error','timeout') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'pending',
  `data_source_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_python_execution` WRITE;
/*!40000 ALTER TABLE `sq_python_execution` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_python_execution` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_query_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_query_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `message_id` bigint DEFAULT NULL,
  `trace_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '全链路追踪ID',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '原始问题',
  `generated_sql` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '生成的SQL',
  `execution_time_ms` int DEFAULT NULL COMMENT '执行耗时',
  `row_count` int DEFAULT NULL COMMENT '返回行数',
  `total_tokens` int DEFAULT '0',
  `model` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用的模型',
  `cost_usd` decimal(10,6) DEFAULT '0.000000' COMMENT '成本(USD)',
  `status` enum('success','error','timeout') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'success',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `event_summary` json DEFAULT NULL COMMENT '事件摘要(JSONL摘要)',
  `duration_ms` int DEFAULT NULL COMMENT '总耗时(ms)',
  `total_tokens_used` int DEFAULT '0' COMMENT '总token消耗',
  `total_cost_usd` decimal(10,6) DEFAULT '0.000000' COMMENT '总成本(USD)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_trace_id` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_query_history` WRITE;
/*!40000 ALTER TABLE `sq_query_history` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_query_history` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `message_id` bigint DEFAULT NULL,
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sections` json NOT NULL COMMENT '报告章节列表',
  `status` enum('generating','completed','error') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'generating',
  `data_source_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `conclusion` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_report` WRITE;
/*!40000 ALTER TABLE `sq_report` DISABLE KEYS */;
/*!40000 ALTER TABLE `sq_report` ENABLE KEYS */;
UNLOCK TABLES;
DROP TABLE IF EXISTS `sq_scenario`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sq_scenario` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '场景名称',
  `code` varchar(50) NOT NULL COMMENT '场景编码',
  `description` text COMMENT '场景描述',
  `icon` varchar(100) DEFAULT NULL COMMENT '图标',
  `category` varchar(50) DEFAULT NULL COMMENT '场景分类',
  `is_system` tinyint DEFAULT '0' COMMENT '是否系统预设',
  `is_enabled` tinyint DEFAULT '1' COMMENT '是否启用',
  `sort_order` int DEFAULT '0' COMMENT '排序',
  `ui_config` text COMMENT '前端UI配置 JSON: {theme, avatar, welcome, capabilities, examples}',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`),
  UNIQUE KEY `code` (`code`),
  KEY `idx_scenario_code` (`code`),
  KEY `idx_scenario_enabled` (`is_enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场景配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `sq_scenario` WRITE;
/*!40000 ALTER TABLE `sq_scenario` DISABLE KEYS */;
INSERT INTO `sq_scenario` VALUES (1,'通用查询','general','通用的数据查询和分析场景','search','query',1,1,1,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),(2,'销售分析','sales_analysis','销售数据分析和报表场景','trend-up','business',1,1,2,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),(3,'用户分析','user_analysis','用户行为分析和画像场景','users','business',1,1,3,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),(4,'财务分析','financial_analysis','财务数据分析和报表场景','dollar-sign','business',1,1,4,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),(5,'运营监控','operations_monitoring','运营指标监控和预警场景','activity','ops',1,1,5,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),(6,'数据挖掘','data_mining','机器学习模型训练和预测场景','brain','mining',1,1,6,'2026-06-19 02:32:37','2026-06-19 02:32:37',0);
/*!40000 ALTER TABLE `sq_scenario` ENABLE KEYS */;

-- 写入前端 UI 配置（theme/avatar/welcome/capabilities/examples）
UPDATE `sq_scenario` SET `ui_config` = '{"theme":{"primary":"#409EFF","gradient":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","background":"#f5f7fa","headerBg":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","cardBg":"rgba(255, 255, 255, 0.9)"},"avatar":{"emoji":"🔍","fallbackColor":"#667eea","size":"large"},"welcome":{"title":"欢迎使用智能问数","subtitle":"不只是查询 — 我可以帮你做完整的数据分析","description":"我是你的智能数据分析助手，可以帮助你查询数据库、生成图表、分析数据。"},"capabilities":[{"icon":"SQL","iconColor":"#E6A23C","title":"智能查询","description":"用自然语言查询数据库，自动生成 SQL"},{"icon":"Py","iconColor":"#409EFF","title":"数据挖掘","description":"Python 分析、建模、预测，支持迭代调试"},{"icon":"📊","iconColor":"#67C23A","title":"可视化","description":"ECharts 图表、仪表盘大屏，自动筛选联动"},{"icon":"📋","iconColor":"#F56C6C","title":"分析报告","description":"多表查询、计算分析、结构化报告生成"}],"examples":["各区域销售额对比，生成柱状图","用Python分析客户流失原因","建一个员工薪资分类预测模型","生成本月销售分析报告","做一个销售仪表盘大屏"]}' WHERE id = 1;

UPDATE `sq_scenario` SET `ui_config` = '{"theme":{"primary":"#67C23A","gradient":"linear-gradient(135deg, #11998e 0%, #38ef7d 100%)","background":"#f0fff4","headerBg":"linear-gradient(135deg, #11998e 0%, #38ef7d 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"📈","fallbackColor":"#11998e","size":"large"},"welcome":{"title":"销售数据分析专家","subtitle":"专注于帮助企业洞察销售趋势、识别机会和风险","description":"我是销售数据分析专家，可以帮助你进行销售趋势分析、商品排行分析、客户行为分析等。"},"capabilities":[{"icon":"📊","iconColor":"#67C23A","title":"销售趋势分析","description":"同比、环比、移动平均趋势分析"},{"icon":"🏆","iconColor":"#E6A23C","title":"商品ABC分析","description":"畅销品识别、滞销品预警、结构优化"},{"icon":"👥","iconColor":"#409EFF","title":"客户行为分析","description":"购买习惯、复购分析、客户分层"},{"icon":"🗺️","iconColor":"#F56C6C","title":"区域销售对比","description":"大区对比、省份排名、城市渗透"}],"examples":["分析最近30天销售趋势，识别TOP3畅销产品","对比各区域销售额，找出增长最快的大区","按商品品类分析销售额和利润率","分析客户复购率和客单价变化趋势","预测下季度销售额并给出目标建议"]}' WHERE id = 2;

UPDATE `sq_scenario` SET `ui_config` = '{"theme":{"primary":"#409EFF","gradient":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","background":"#f0f7ff","headerBg":"linear-gradient(135deg, #667eea 0%, #764ba2 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"👥","fallbackColor":"#667eea","size":"large"},"welcome":{"title":"用户分析专家","subtitle":"专注于用户行为洞察、用户画像和用户增长","description":"我是用户分析专家，可以帮助你进行用户画像分析、生命周期分析、行为路径分析等。"},"capabilities":[{"icon":"👤","iconColor":"#409EFF","title":"用户画像分析","description":"基础属性、行为特征、偏好标签"},{"icon":"🔄","iconColor":"#67C23A","title":"生命周期分析","description":"新增、活跃、留存、流失分析"},{"icon":"🎯","iconColor":"#E6A23C","title":"行为路径分析","description":"漏斗分析、转化分析、路径优化"},{"icon":"💰","iconColor":"#F56C6C","title":"用户价值分析","description":"RFM模型、CLV预测、价值分层"}],"examples":["分析DAU和MAU的变化趋势，找出流失原因","计算次日留存和7日留存，评估用户粘性","用RFM模型对用户进行分群和精细化运营","分析注册到购买的转化漏斗，找出流失点","预测用户生命周期价值(LTV)和获客成本"]}' WHERE id = 3;

UPDATE `sq_scenario` SET `ui_config` = '{"theme":{"primary":"#E6A23C","gradient":"linear-gradient(135deg, #f093fb 0%, #f5576c 100%)","background":"#fffaf0","headerBg":"linear-gradient(135deg, #f093fb 0%, #f5576c 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"💰","fallbackColor":"#f5576c","size":"large"},"welcome":{"title":"财务分析专家","subtitle":"专注于企业财务数据分析和经营决策支持","description":"我是财务分析专家，可以帮助你进行财务报表分析、财务比率分析、成本费用分析等。"},"capabilities":[{"icon":"📊","iconColor":"#E6A23C","title":"财务报表分析","description":"资产负债表、利润表、现金流量表"},{"icon":"📈","iconColor":"#67C23A","title":"财务比率分析","description":"盈利能力、偿债能力、运营能力指标"},{"icon":"💵","iconColor":"#409EFF","title":"成本费用分析","description":"成本结构、费用控制、预算执行"},{"icon":"⚠️","iconColor":"#F56C6C","title":"财务风险预警","description":"流动性风险、偿债风险、经营风险"}],"examples":["分析本季度财务报表，计算关键财务比率","对比今年与去年的利润表，分析收入增长驱动因素","评估公司的偿债能力和财务风险","分析各部门的费用执行情况和预算差异","预测下季度现金流并给出资金管理建议"]}' WHERE id = 4;

UPDATE `sq_scenario` SET `ui_config` = '{"theme":{"primary":"#F56C6C","gradient":"linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%)","background":"#fff5f5","headerBg":"linear-gradient(135deg, #ff6b6b 0%, #ee5a24 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"📡","fallbackColor":"#ee5a24","size":"large"},"welcome":{"title":"运营监控专家","subtitle":"专注于实时监控业务指标、发现异常和及时预警","description":"我是运营监控专家，可以帮助你进行实时指标监控、异常检测、业务漏斗监控等。"},"capabilities":[{"icon":"📊","iconColor":"#F56C6C","title":"实时指标监控","description":"DAU、订单量、GMV、转化率等核心指标"},{"icon":"🔔","iconColor":"#E6A23C","title":"异常检测预警","description":"突增突降、趋势偏离、阈值超限"},{"icon":"🔄","iconColor":"#67C23A","title":"业务漏斗监控","description":"转化漏斗、流失分析、路径优化"},{"icon":"🖥️","iconColor":"#409EFF","title":"系统健康监控","description":"性能指标、质量指标、资源监控"}],"examples":["监控今日DAU和订单量，发现异常波动","分析注册到支付的转化漏斗，找出流失环节","检查系统性能指标，发现性能瓶颈","监控各渠道的用户获取成本和质量","生成今日运营监控日报"]}' WHERE id = 5;

UPDATE `sq_scenario` SET `ui_config` = '{"theme":{"primary":"#909399","gradient":"linear-gradient(135deg, #434343 0%, #000000 100%)","background":"#f5f5f5","headerBg":"linear-gradient(135deg, #434343 0%, #000000 100%)","cardBg":"rgba(255, 255, 255, 0.95)"},"avatar":{"emoji":"🧠","fallbackColor":"#434343","size":"large"},"welcome":{"title":"数据挖掘专家","subtitle":"专注于机器学习模型训练、预测和发现数据规律","description":"我是数据挖掘专家，可以帮助你进行数据探索、特征工程、模型训练、预测分析等。"},"capabilities":[{"icon":"🔍","iconColor":"#909399","title":"数据探索分析","description":"数据分布、相关性分析、特征发现"},{"icon":"⚙️","iconColor":"#409EFF","title":"特征工程","description":"特征选择、特征变换、特征优化"},{"icon":"🤖","iconColor":"#67C23A","title":"模型训练评估","description":"分类、回归、聚类算法应用"},{"icon":"📈","iconColor":"#E6A23C","title":"预测和解释","description":"模型预测、结果解释、可视化展示"}],"examples":["探索销售数据的特征分布和相关性","构建客户流失预测模型，评估准确率","用K-means对用户进行聚类分析","训练一个销量预测模型并优化参数","解释模型特征重要性，找出关键影响因素"]}' WHERE id = 6;

UNLOCK TABLES;

--
-- 角色-场景授权关联表
--
DROP TABLE IF EXISTS `sq_role_scenario`;
CREATE TABLE `sq_role_scenario` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role` varchar(32) NOT NULL COMMENT '角色名（与 sq_user.role 对齐）',
  `scenario_id` bigint NOT NULL COMMENT '场景ID',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_scenario` (`role`, `scenario_id`),
  KEY `idx_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-场景授权表';

LOCK TABLES `sq_role_scenario` WRITE;
/*!40000 ALTER TABLE `sq_role_scenario` DISABLE KEYS */;
INSERT INTO `sq_role_scenario` VALUES
  (1,'admin',1,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),
  (2,'admin',2,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),
  (3,'admin',3,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),
  (4,'admin',4,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),
  (5,'admin',5,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),
  (6,'admin',6,'2026-06-19 02:32:37','2026-06-19 02:32:37',0),
  (7,'user',1,'2026-06-19 02:32:37','2026-06-19 02:32:37',0);
/*!40000 ALTER TABLE `sq_role_scenario` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

