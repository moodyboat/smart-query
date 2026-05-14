package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.ModelExecution;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.ModelExecutionMapper;
import com.smartquery.mapper.DataSourceMapper;
import com.smartquery.python.PythonExecutor;
import com.smartquery.python.PythonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MiningService {

    private final MiningModelMapper miningModelMapper;
    private final ModelExecutionMapper modelExecutionMapper;
    private final DataSourceMapper dataSourceMapper;
    private final PythonExecutor pythonExecutor;
    private final DataSourceManager dataSourceManager;
    private final ObjectMapper objectMapper;

    public MiningModel createModel(MiningModel model) {
        model.setStatus("draft");
        model.setVersion(1);
        model.setDeleted(0);
        miningModelMapper.insert(model);
        return model;
    }

    public MiningModel updateModel(Long id, MiningModel updates) {
        MiningModel existing = miningModelMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("模型不存在: " + id);

        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getAlgorithm() != null) existing.setAlgorithm(updates.getAlgorithm());
        if (updates.getHyperparameters() != null) existing.setHyperparameters(updates.getHyperparameters());
        if (updates.getSourceTable() != null) existing.setSourceTable(updates.getSourceTable());
        if (updates.getFeatureColumns() != null) existing.setFeatureColumns(updates.getFeatureColumns());
        if (updates.getTargetColumn() != null) existing.setTargetColumn(updates.getTargetColumn());
        if (updates.getPreprocessing() != null) existing.setPreprocessing(updates.getPreprocessing());
        if (updates.getModelType() != null) existing.setModelType(updates.getModelType());
        if (updates.getScheduleCron() != null) existing.setScheduleCron(updates.getScheduleCron());
        if (updates.getScheduleEnabled() != null) existing.setScheduleEnabled(updates.getScheduleEnabled());

        miningModelMapper.updateById(existing);
        return existing;
    }

    public MiningModel trainModel(Long modelId, String triggerType) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);

        ModelExecution execution = new ModelExecution();
        execution.setModelId(modelId);
        execution.setTriggerType(triggerType != null ? triggerType : "manual");
        execution.setStatus("running");
        execution.setHyperparameters(model.getHyperparameters());
        modelExecutionMapper.insert(execution);

        model.setStatus("training");
        miningModelMapper.updateById(model);

        try {
            String pythonCode = buildTrainingScript(model);
            PythonResult result = pythonExecutor.execute(pythonCode, model.getDataSourceId(), 300000);

            execution.setExecutionTimeMs(result.executionTimeMs());
            execution.setExecutionLog(truncateLog(result.stdout(), 50000));

            if (result.exitCode() == 0) {
                Map<String, Object> parsed = parseTrainingOutput(result.stdout());
                execution.setMetrics(toJson(parsed.get("metrics")));
                execution.setStatus("success");

                model.setStatus("trained");
                model.setMetrics(toJson(parsed.get("metrics")));
                model.setFeatureImportance(toJson(parsed.get("feature_importance")));
                model.setTrainingLog(truncateLog(result.stdout(), 20000));
                model.setVersion(model.getVersion() + 1);
                if (parsed.get("model_path") != null) {
                    model.setModelPath(String.valueOf(parsed.get("model_path")));
                }
            } else {
                execution.setStatus("failed");
                execution.setExecutionLog(truncateLog(result.stderr(), 50000));
                model.setStatus("failed");
            }

            model.setLastRunAt(LocalDateTime.now());
            miningModelMapper.updateById(model);
            modelExecutionMapper.updateById(execution);

            return model;
        } catch (Exception e) {
            log.error("[MINING] Training failed for model {}: {}", modelId, e.getMessage());
            execution.setStatus("failed");
            execution.setExecutionLog(e.getMessage());
            modelExecutionMapper.updateById(execution);

            model.setStatus("failed");
            model.setTrainingLog(e.getMessage());
            miningModelMapper.updateById(model);
            return model;
        }
    }

    public MiningModel publishModel(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        if (!"trained".equals(model.getStatus())) {
            throw new IllegalStateException("只有训练完成的模型才能发布，当前状态: " + model.getStatus());
        }
        model.setStatus("published");
        miningModelMapper.updateById(model);
        return model;
    }

    public MiningModel offlineModel(Long modelId) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        model.setStatus("offline");
        model.setScheduleEnabled(false);
        miningModelMapper.updateById(model);
        return model;
    }

    public MiningModel updateHyperparameters(Long modelId, String hyperparametersJson) {
        MiningModel model = miningModelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("模型不存在: " + modelId);
        model.setHyperparameters(hyperparametersJson);
        miningModelMapper.updateById(model);
        return model;
    }

    private String buildTrainingScript(MiningModel model) {
        DataSource ds = dataSourceMapper.selectById(model.getDataSourceId());
        String dbUrl = ds != null ? buildSqlalchemyUrl(ds) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("import pandas as pd\n");
        sb.append("import numpy as np\n");
        sb.append("import json\n");
        sb.append("import os\n");
        sb.append("from sqlalchemy import create_engine\n");
        sb.append("import joblib\n");
        sb.append("\n");

        sb.append("engine = create_engine('").append(dbUrl).append("')\n");
        sb.append("df = pd.read_sql('SELECT * FROM `").append(model.getSourceTable()).append("`', engine)\n");
        sb.append("print(f'[INFO] Loaded {len(df)} rows, {len(df.columns)} columns')\n\n");

        // Preprocessing
        sb.append("preprocessing = ").append(model.getPreprocessing() != null ? model.getPreprocessing() : "{}").append("\n");
        sb.append("_hm = preprocessing.get('handleMissing', 'drop')\n");
        sb.append("if _hm == 'drop':\n");
        sb.append("    df = df.dropna()\n");
        sb.append("elif _hm == 'fill_mean':\n");
        sb.append("    for c in df.select_dtypes(include=['number']).columns:\n");
        sb.append("        df[c] = df[c].fillna(df[c].mean())\n");
        sb.append("    df = df.dropna()\n");
        sb.append("elif _hm == 'fill_median':\n");
        sb.append("    for c in df.select_dtypes(include=['number']).columns:\n");
        sb.append("        df[c] = df[c].fillna(df[c].median())\n");
        sb.append("    df = df.dropna()\n");
        sb.append("print(f'[INFO] After handleMissing({_hm}): {len(df)} rows')\n");
        sb.append("\n");

        // Feature/target split — normalize to Python list
        sb.append("_fc_raw = ").append(model.getFeatureColumns()).append("\n");
        sb.append("if isinstance(_fc_raw, str):\n");
        sb.append("    feature_cols = [c.strip() for c in _fc_raw.split(',') if c.strip()]\n");
        sb.append("else:\n");
        sb.append("    feature_cols = list(_fc_raw)\n");
        sb.append("X = df[feature_cols]\n");

        if (model.getTargetColumn() != null && !model.getTargetColumn().isBlank()) {
            sb.append("y = df['").append(model.getTargetColumn()).append("']\n");
        } else {
            sb.append("y = None\n");
        }
        sb.append("\n");

        // Encoding categorical features
        sb.append("_enc = preprocessing.get('encoding', 'label')\n");
        sb.append("cat_cols = X.select_dtypes(include=['object']).columns.tolist()\n");
        sb.append("if cat_cols:\n");
        sb.append("    if _enc == 'onehot':\n");
        sb.append("        X = pd.get_dummies(X, columns=cat_cols)\n");
        sb.append("    else:\n");
        sb.append("        from sklearn.preprocessing import LabelEncoder\n");
        sb.append("        le = LabelEncoder()\n");
        sb.append("        for c in cat_cols:\n");
        sb.append("            X[c] = le.fit_transform(X[c].astype(str))\n");
        sb.append("\n");

        // Feature scaling
        sb.append("_sc = preprocessing.get('scaling', 'none')\n");
        sb.append("num_cols = X.select_dtypes(include=['number']).columns.tolist()\n");
        sb.append("if _sc == 'standard' and num_cols:\n");
        sb.append("    from sklearn.preprocessing import StandardScaler\n");
        sb.append("    X[num_cols] = StandardScaler().fit_transform(X[num_cols])\n");
        sb.append("elif _sc == 'minmax' and num_cols:\n");
        sb.append("    from sklearn.preprocessing import MinMaxScaler\n");
        sb.append("    X[num_cols] = MinMaxScaler().fit_transform(X[num_cols])\n");
        sb.append("\n");

        // Algorithm selection and training
        String hyperparams = model.getHyperparameters() != null ? model.getHyperparameters() : "{}";
        sb.append("params = ").append(hyperparams).append("\n");
        sb.append("algorithm = '").append(model.getAlgorithm()).append("'\n\n");

        sb.append(buildAlgorithmBlock(model.getAlgorithm()));

        sb.append("\n# Train\n");
        sb.append("from sklearn.model_selection import train_test_split\n");
        sb.append("if y is not None:\n");
        sb.append("    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)\n");
        sb.append("else:\n");
        sb.append("    X_train = X\n");
        sb.append("    X_test = None\n");
        sb.append("    y_train = None\n");
        sb.append("    y_test = None\n\n");

        sb.append("clf.fit(X_train, y_train if y is not None else X_train)\n\n");

        // Evaluation
        sb.append("import sklearn.metrics as metrics\n");
        sb.append("result = {}\n");
        sb.append("if y_test is not None:\n");
        sb.append("    y_pred = clf.predict(X_test)\n");
        sb.append("    if 'classification' in '").append(model.getModelType()).append("':\n");
        sb.append("        result['accuracy'] = round(metrics.accuracy_score(y_test, y_pred), 4)\n");
        sb.append("        result['precision'] = round(metrics.precision_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['recall'] = round(metrics.recall_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("        result['f1'] = round(metrics.f1_score(y_test, y_pred, average='weighted', zero_division=0), 4)\n");
        sb.append("    else:\n");
        sb.append("        result['mse'] = round(metrics.mean_squared_error(y_test, y_pred), 4)\n");
        sb.append("        result['rmse'] = round(np.sqrt(metrics.mean_squared_error(y_test, y_pred)), 4)\n");
        sb.append("        result['r2'] = round(metrics.r2_score(y_test, y_pred), 4)\n");
        sb.append("else:\n");
        sb.append("    result['inertia'] = getattr(clf, 'inertia_', None)\n");
        sb.append("    if hasattr(clf, 'labels_'):\n");
        sb.append("        result['n_clusters'] = len(set(clf.labels_))\n\n");

        // Feature importance
        sb.append("fi = {}\n");
        sb.append("if hasattr(clf, 'feature_importances_'):\n");
        sb.append("    fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.feature_importances_]))\n");
        sb.append("elif hasattr(clf, 'coef_'):\n");
        sb.append("    fi = dict(zip(X_train.columns, [round(float(v), 4) for v in clf.coef_.flatten()]))\n\n");

        // Save model
        sb.append("_workspace = '/tmp/smartquery-workspace'\n");
        sb.append("os.makedirs(_workspace, exist_ok=True)\n");
        sb.append("_model_path = os.path.join(_workspace, 'model_").append(model.getId()).append("_v' + clf.__class__.__name__ + '.pkl')\n");
        sb.append("joblib.dump(clf, _model_path)\n\n");

        // Output results as JSON marker
        sb.append("print('[TRAIN_RESULT] ' + json.dumps({'metrics': result, 'feature_importance': fi, 'model_path': _model_path}))\n");

        return sb.toString();
    }

    private String buildAlgorithmBlock(String algorithm) {
        return switch (algorithm) {
            case "random_forest" -> """
                from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
                model_cls = RandomForestClassifier if y is not None and y.nunique() <= 20 else RandomForestRegressor
                clf = model_cls(**params)
                """;
            case "xgboost" -> """
                from xgboost import XGBClassifier, XGBRegressor
                model_cls = XGBClassifier if y is not None and y.nunique() <= 20 else XGBRegressor
                clf = model_cls(**params)
                """;
            case "decision_tree" -> """
                from sklearn.tree import DecisionTreeClassifier, DecisionTreeRegressor
                model_cls = DecisionTreeClassifier if y is not None and y.nunique() <= 20 else DecisionTreeRegressor
                clf = model_cls(**params)
                """;
            case "logistic_regression" -> """
                from sklearn.linear_model import LogisticRegression
                from sklearn.preprocessing import StandardScaler
                scaler = StandardScaler()
                X = pd.DataFrame(scaler.fit_transform(X), columns=X.columns)
                clf = LogisticRegression(**params)
                """;
            case "svm" -> """
                from sklearn.svm import SVC, SVR
                model_cls = SVC if y is not None and y.nunique() <= 20 else SVR
                clf = model_cls(**params)
                """;
            case "knn" -> """
                from sklearn.neighbors import KNeighborsClassifier, KNeighborsRegressor
                model_cls = KNeighborsClassifier if y is not None and y.nunique() <= 20 else KNeighborsRegressor
                clf = model_cls(**params)
                """;
            case "kmeans" -> """
                from sklearn.cluster import KMeans
                params.setdefault('n_clusters', 3)
                clf = KMeans(**params)
                """;
            case "gradient_boosting" -> """
                from sklearn.ensemble import GradientBoostingClassifier, GradientBoostingRegressor
                model_cls = GradientBoostingClassifier if y is not None and y.nunique() <= 20 else GradientBoostingRegressor
                clf = model_cls(**params)
                """;
            case "lightgbm" -> """
                from lightgbm import LGBMClassifier, LGBMRegressor
                model_cls = LGBMClassifier if y is not None and y.nunique() <= 20 else LGBMRegressor
                clf = model_cls(**params)
                """;
            default -> """
                from sklearn.ensemble import RandomForestClassifier
                clf = RandomForestClassifier(**params)
                """;
        };
    }

    private String buildSqlalchemyUrl(DataSource ds) {
        String user = URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8);
        return "mysql+pymysql://%s:%s@%s:%d/%s".formatted(
            user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTrainingOutput(String stdout) {
        Map<String, Object> result = new HashMap<>();
        if (stdout == null) return result;

        for (String line : stdout.split("\n")) {
            if (line.contains("[TRAIN_RESULT]")) {
                try {
                    String json = line.substring(line.indexOf("[TRAIN_RESULT]") + 14).trim();
                    result = objectMapper.readValue(json, Map.class);
                } catch (Exception e) {
                    log.warn("[MINING] Failed to parse training result: {}", e.getMessage());
                }
                break;
            }
        }
        return result;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String truncateLog(String log, int maxLen) {
        if (log == null || log.length() <= maxLen) return log;
        return log.substring(0, maxLen) + "\n... (truncated)";
    }
}
