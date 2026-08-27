package com.smartquery.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.UserRoles;
import com.smartquery.entity.RoleScenario;
import com.smartquery.entity.Scenario;
import com.smartquery.entity.User;
import com.smartquery.mapper.RoleScenarioMapper;
import com.smartquery.mapper.ScenarioMapper;
import com.smartquery.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 首次启动时注入默认管理员账号（密码 BCrypt 加密）。
 * 项目已移除 Flyway，schema 由 smart_query_seed.sql dump 导入；
 * 此处用 CREATE TABLE IF NOT EXISTS / ALTER TABLE ADD COLUMN IF NOT EXISTS 兜底，
 * 保证表/列一定存在（兼容本地老库与 Docker 全新库）。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    // DDL 兼容 MySQL 与 DM8（COMPATIBLE_MODE=4）：
    //   - 去掉 ON UPDATE CURRENT_TIMESTAMP（DM 不支持，由 MyBatis-Plus MetaObjectHandler 自动填充 updated_at）
    //   - UNIQUE KEY → CONSTRAINT ... UNIQUE（DM 不认 UNIQUE KEY 子句，MySQL 也兼容 CONSTRAINT 写法）
    //   - 去掉 ENGINE/CHARSET 子句（DM 忽略，DM 用 CHARSET 参数控制）
    private static final String CREATE_SQ_USER_SQL = """
        CREATE TABLE IF NOT EXISTS sq_user (
            id            BIGINT       NOT NULL AUTO_INCREMENT,
            username      VARCHAR(64)  NOT NULL,
            password_hash VARCHAR(100) NOT NULL,
            display_name  VARCHAR(64),
            email         VARCHAR(128),
            role          VARCHAR(32)  NOT NULL DEFAULT 'user',
            enabled       TINYINT      NOT NULL DEFAULT 1,
            last_login_at DATETIME,
            created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted       TINYINT      NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_username UNIQUE (username)
        )
        """;

    private static final String CREATE_SQ_ROLE_SCENARIO_SQL = """
        CREATE TABLE IF NOT EXISTS sq_role_scenario (
            id           BIGINT      NOT NULL AUTO_INCREMENT,
            role         VARCHAR(32) NOT NULL,
            scenario_id  BIGINT      NOT NULL,
            created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted      TINYINT     NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            CONSTRAINT uk_role_scenario UNIQUE (role, scenario_id)
        )
        """;

    private static final String CREATE_SQ_TASK_EVENT_SQL = """
        CREATE TABLE IF NOT EXISTS sq_task_event (
            id            BIGINT       NOT NULL AUTO_INCREMENT,
            topic         VARCHAR(160) NOT NULL,
            owner_user_id VARCHAR(64)  NOT NULL,
            event_name    VARCHAR(50)  NOT NULL,
            payload       TEXT         NOT NULL,
            terminal      TINYINT      NOT NULL DEFAULT 0,
            created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id)
        )
        """;

    private static final String[] ALTER_SQ_MINING_GOVERNANCE_SQLS = {
        "ALTER TABLE sq_mining_model ADD COLUMN artifact_sha256 VARCHAR(64)",
        "ALTER TABLE sq_mining_model ADD COLUMN artifact_schema_version INT",
        "ALTER TABLE sq_model_execution ADD COLUMN progress_percent INT DEFAULT 0",
        "ALTER TABLE sq_model_execution ADD COLUMN current_stage VARCHAR(50)",
        "ALTER TABLE sq_model_execution ADD COLUMN progress_message VARCHAR(500)",
        "ALTER TABLE sq_model_execution ADD COLUMN cancel_requested TINYINT DEFAULT 0",
        "ALTER TABLE sq_model_execution ADD COLUMN artifact_path VARCHAR(1000)",
        "ALTER TABLE sq_model_execution ADD COLUMN artifact_sha256 VARCHAR(64)",
        "ALTER TABLE sq_model_execution ADD COLUMN artifact_schema_version INT",
        "ALTER TABLE sq_model_execution ADD COLUMN started_at DATETIME",
        "ALTER TABLE sq_model_execution ADD COLUMN finished_at DATETIME",
        "ALTER TABLE sq_model_execution ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP",
        "ALTER TABLE sq_mining_model ADD COLUMN positive_class VARCHAR(255)",
        "ALTER TABLE sq_mining_model ADD COLUMN group_columns TEXT",
        "ALTER TABLE sq_mining_model ADD COLUMN oos_table VARCHAR(255)",
        "ALTER TABLE sq_mining_model ADD COLUMN oos_filter TEXT",
        "ALTER TABLE sq_mining_model ADD COLUMN calibration_method VARCHAR(20) DEFAULT 'none'",
        "ALTER TABLE sq_mining_model ADD COLUMN threshold_policy TEXT",
        "ALTER TABLE sq_mining_model ADD COLUMN governance_policy TEXT",
        "ALTER TABLE sq_mining_model ADD COLUMN evaluation_status VARCHAR(30) DEFAULT 'pending'",
        "ALTER TABLE sq_mining_model ADD COLUMN approved_by_user_id VARCHAR(64)",
        "ALTER TABLE sq_mining_model ADD COLUMN approved_at DATETIME",
        "ALTER TABLE sq_mining_model ADD COLUMN monitoring_baseline TEXT",
        "ALTER TABLE sq_mining_model ADD COLUMN last_drift_metrics TEXT",
        "ALTER TABLE sq_mining_model ADD COLUMN last_drift_at DATETIME"
    };

    /**
     * 给 sq_scenario 加 ui_config 列（若已存在则跳过；MySQL 8.0.29+ 支持 IF NOT EXISTS，
     * DM8 不支持 ADD COLUMN IF NOT EXISTS 但 ORA-compatible 模式可重复执行报错由 catch 兜底）。
     */
    private static final String ALTER_SQ_SCENARIO_ADD_UI_CONFIG_SQL =
        "ALTER TABLE sq_scenario ADD COLUMN ui_config TEXT";

    /**
     * 给 sq_mining_model 加 user_id 列，用于多租户隔离（同 sq_conversation.user_id）。
     * 列已存在或老库不支持时由 catch 吞错。
     */
    private static final String ALTER_SQ_MINING_MODEL_ADD_USER_ID_SQL =
        "ALTER TABLE sq_mining_model ADD COLUMN user_id VARCHAR(50)";

    private static final String ALTER_SQ_MINING_PIPELINE_ADD_USER_ID_SQL =
        "ALTER TABLE sq_mining_pipeline ADD COLUMN user_id VARCHAR(50)";

    private static final String ALTER_SQ_MODEL_EXECUTION_ADD_TRIGGER_USER_SQL =
        "ALTER TABLE sq_model_execution ADD COLUMN triggered_by_user_id VARCHAR(50)";

    /**
     * 给 sq_conversation 加 scenario 列，用于刷新页面后恢复场景上下文。
     * 列已存在或老库不支持时由 catch 吞错。
     */
    private static final String ALTER_SQ_CONVERSATION_ADD_SCENARIO_SQL =
        "ALTER TABLE sq_conversation ADD COLUMN scenario VARCHAR(64)";

    /** 旧库中的报告和仪表盘表缺少 BaseEntity 所需的逻辑删除列。 */
    private static final String[] ALTER_LOGICAL_DELETE_SQLS = {
        "ALTER TABLE sq_report ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0",
        "ALTER TABLE sq_dashboard ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0"
    };

    /**
     * 场景化隔离：给 sq_scenario 加 4 列。
     * 所有列 nullable + 默认 NULL，老场景未配置时行为完全等同现状。
     * 列已存在或老库不支持时由 catch 吞错。
     */
    private static final String[] ALTER_SQ_SCENARIO_ISOLATION_SQLS = {
        "ALTER TABLE sq_scenario ADD COLUMN data_source_id BIGINT",
        "ALTER TABLE sq_scenario ADD COLUMN schema_name VARCHAR(128)",
        "ALTER TABLE sq_scenario ADD COLUMN allowed_tables TEXT",
        "ALTER TABLE sq_scenario ADD COLUMN prompt_override TEXT"
    };

    private final UserMapper userMapper;
    private final ScenarioMapper scenarioMapper;
    private final RoleScenarioMapper roleScenarioMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${smart-query.auth.admin-username:admin}")
    private String adminUsername;

    @Value("${smart-query.auth.admin-password:admin123}")
    private String adminPassword;

    @Value("${smart-query.auth.admin-display-name:系统管理员}")
    private String adminDisplayName;

    @Override
    public void run(String... args) {
        jdbcTemplate.execute(CREATE_SQ_USER_SQL);
        jdbcTemplate.execute(CREATE_SQ_ROLE_SCENARIO_SQL);
        jdbcTemplate.execute(CREATE_SQ_TASK_EVENT_SQL);
        try {
            jdbcTemplate.execute("CREATE INDEX idx_task_event_replay ON sq_task_event(topic, owner_user_id, id)");
        } catch (Exception ignored) { /* index already exists */ }
        try {
            jdbcTemplate.execute("CREATE INDEX idx_task_event_created ON sq_task_event(created_at)");
        } catch (Exception ignored) { /* index already exists */ }

        for (String sql : ALTER_SQ_MINING_GOVERNANCE_SQLS) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.debug("[SEED] 建模治理列已存在或加列失败（可忽略）: {}", e.getMessage());
            }
        }
        try {
            jdbcTemplate.execute("ALTER TABLE sq_model_execution MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'pending'");
        } catch (Exception e) {
            log.debug("[SEED] sq_model_execution.status 已兼容或修改失败（可忽略）: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE sq_model_execution MODIFY COLUMN trigger_type VARCHAR(30) NOT NULL DEFAULT 'manual'");
        } catch (Exception e) {
            log.debug("[SEED] sq_model_execution.trigger_type 已兼容或修改失败（可忽略）: {}", e.getMessage());
        }

        // 兼容老库：尝试加 ui_config 列；列已存在时报错被吞掉
        try {
            jdbcTemplate.execute(ALTER_SQ_SCENARIO_ADD_UI_CONFIG_SQL);
            log.info("[SEED] sq_scenario 加列 ui_config 成功");
        } catch (Exception e) {
            log.debug("[SEED] sq_scenario.ui_config 已存在或加列失败（可忽略）: {}", e.getMessage());
        }

        // 兼容老库：尝试给 sq_mining_model 加 user_id 列，用于多租户隔离
        try {
            jdbcTemplate.execute(ALTER_SQ_MINING_MODEL_ADD_USER_ID_SQL);
            log.info("[SEED] sq_mining_model 加列 user_id 成功");
        } catch (Exception e) {
            log.debug("[SEED] sq_mining_model.user_id 已存在或加列失败（可忽略）: {}", e.getMessage());
        }

        try {
            jdbcTemplate.execute(ALTER_SQ_MINING_PIPELINE_ADD_USER_ID_SQL);
            log.info("[SEED] sq_mining_pipeline 加列 user_id 成功");
        } catch (Exception e) {
            log.debug("[SEED] sq_mining_pipeline.user_id 已存在或加列失败（可忽略）: {}", e.getMessage());
        }

        try {
            jdbcTemplate.execute(ALTER_SQ_MODEL_EXECUTION_ADD_TRIGGER_USER_SQL);
            log.info("[SEED] sq_model_execution 加列 triggered_by_user_id 成功");
        } catch (Exception e) {
            log.debug("[SEED] sq_model_execution.triggered_by_user_id 已存在或加列失败（可忽略）: {}", e.getMessage());
        }

        // 兼容老库：给 sq_scenario 加场景化隔离 4 列（数据源/schema/表白名单/prompt 覆盖）
        for (String sql : ALTER_SQ_SCENARIO_ISOLATION_SQLS) {
            try {
                jdbcTemplate.execute(sql);
                log.info("[SEED] sq_scenario 加列成功: {}", sql.replaceAll(".*ADD COLUMN (\\S+).*", "$1"));
            } catch (Exception e) {
                log.debug("[SEED] sq_scenario 加列已存在或失败（可忽略）: {}", e.getMessage());
            }
        }

        // 兼容老库：给 sq_conversation 加 scenario 列（刷新页面恢复场景）
        try {
            jdbcTemplate.execute(ALTER_SQ_CONVERSATION_ADD_SCENARIO_SQL);
            log.info("[SEED] sq_conversation 加列 scenario 成功");
        } catch (Exception e) {
            log.debug("[SEED] sq_conversation.scenario 已存在或加列失败（可忽略）: {}", e.getMessage());
        }

        for (String sql : ALTER_LOGICAL_DELETE_SQLS) {
            try {
                jdbcTemplate.execute(sql);
                log.info("[SEED] 补充逻辑删除列成功: {}", sql);
            } catch (Exception e) {
                log.debug("[SEED] 逻辑删除列已存在或补充失败（可忽略）: {}", e.getMessage());
            }
        }

        seedDefaultAdmin();
        seedDefaultRoleScenarios();
        seedAlgorithmCatalog();
    }

    private void seedAlgorithmCatalog() {
        String[][] categories = {
            {"random_forest", "集成学习"}, {"xgboost", "集成学习"},
            {"gradient_boosting", "集成学习"}, {"lightgbm", "集成学习"},
            {"decision_tree", "树模型"}, {"logistic_regression", "线性模型"},
            {"svm", "核方法"}, {"knn", "近邻方法"}, {"kmeans", "聚类"}
        };
        for (String[] item : categories) {
            jdbcTemplate.update("UPDATE sq_algorithm SET category = ? WHERE algorithm_id = ? AND is_builtin = 1",
                item[1], item[0]);
        }

        ensureAlgorithm("neural_network", "多层感知机 (MLP)",
            "基于反向传播的全连接神经网络，支持分类和回归，可配置网络宽度、层数、激活函数、学习率与早停。",
            "[\"classification\",\"regression\"]",
            "[{\"key\":\"hidden_layer_size\",\"label\":\"每层神经元\",\"type\":\"int\",\"min\":4,\"max\":1024,\"step\":4,\"defaultValue\":100},"
                + "{\"key\":\"hidden_layers\",\"label\":\"隐藏层数\",\"type\":\"int\",\"min\":1,\"max\":8,\"step\":1,\"defaultValue\":2},"
                + "{\"key\":\"activation\",\"label\":\"激活函数\",\"type\":\"select\",\"options\":[\"relu\",\"tanh\",\"logistic\"],\"defaultValue\":\"relu\"},"
                + "{\"key\":\"solver\",\"label\":\"优化器\",\"type\":\"select\",\"options\":[\"adam\",\"sgd\",\"lbfgs\"],\"defaultValue\":\"adam\"},"
                + "{\"key\":\"learning_rate_init\",\"label\":\"初始学习率\",\"type\":\"float\",\"min\":0.00001,\"max\":1,\"step\":0.0001,\"defaultValue\":0.001},"
                + "{\"key\":\"alpha\",\"label\":\"L2正则系数\",\"type\":\"float\",\"min\":0,\"max\":1,\"step\":0.0001,\"defaultValue\":0.0001},"
                + "{\"key\":\"max_iter\",\"label\":\"最大迭代次数\",\"type\":\"int\",\"min\":50,\"max\":5000,\"step\":50,\"defaultValue\":500},"
                + "{\"key\":\"early_stopping\",\"label\":\"启用早停\",\"type\":\"boolean\",\"defaultValue\":true}]",
            "from sklearn.neural_network import MLPClassifier, MLPRegressor\n"
                + "hidden_size = int(params.pop('hidden_layer_size', 100))\n"
                + "hidden_layers = int(params.pop('hidden_layers', 2))\n"
                + "params['hidden_layer_sizes'] = tuple([hidden_size] * hidden_layers)\n"
                + "params.setdefault('random_state', 42)\n"
                + "model_cls = MLPClassifier if _model_type == 'classification' else MLPRegressor\n"
                + "clf = model_cls(**params)", "🧠", "神经网络");

        ensureAlgorithm("linear_regression", "线性回归", "可解释的回归基线模型，适合线性关系与基准比较。",
            "[\"regression\"]",
            "[{\"key\":\"fit_intercept\",\"label\":\"拟合截距\",\"type\":\"boolean\",\"defaultValue\":true},"
                + "{\"key\":\"positive\",\"label\":\"限制正系数\",\"type\":\"boolean\",\"defaultValue\":false}]",
            "from sklearn.linear_model import LinearRegression\nclf = LinearRegression(**params)", "📏", "线性模型");

        ensureAlgorithm("naive_bayes", "高斯朴素贝叶斯", "训练快速的概率分类基线，适合连续特征和小样本场景。",
            "[\"classification\"]",
            "[{\"key\":\"var_smoothing\",\"label\":\"方差平滑\",\"type\":\"float\",\"min\":0.000000000001,\"max\":0.1,\"step\":0.000000001,\"defaultValue\":0.000000001}]",
            "from sklearn.naive_bayes import GaussianNB\nclf = GaussianNB(**params)", "🎲", "概率模型");

        ensureAlgorithm("isolation_forest", "孤立森林", "通过随机切分隔离异常点，适合无监督异常检测。",
            "[\"anomaly_detection\"]",
            "[{\"key\":\"n_estimators\",\"label\":\"树的数量\",\"type\":\"int\",\"min\":10,\"max\":1000,\"step\":10,\"defaultValue\":100},"
                + "{\"key\":\"contamination\",\"label\":\"异常比例\",\"type\":\"float\",\"min\":0.001,\"max\":0.5,\"step\":0.01,\"defaultValue\":0.05}]",
            "from sklearn.ensemble import IsolationForest\nparams.setdefault('random_state', 42)\nclf = IsolationForest(**params)", "🚨", "异常检测");
    }

    private void ensureAlgorithm(String algorithmId, String name, String description,
                                 String modelTypes, String paramsSchema, String pythonCode,
                                 String icon, String category) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sq_algorithm WHERE algorithm_id = ?", Integer.class, algorithmId);
        if (count != null && count > 0) return;
        jdbcTemplate.update("""
            INSERT INTO sq_algorithm
              (algorithm_id, name, description, model_types, params_schema, python_code_template,
               is_builtin, icon, category, created_at, updated_at, deleted)
            VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """, algorithmId, name, description, modelTypes, paramsSchema, pythonCode, icon, category);
        log.info("[SEED] 新增内置算法模板: {} ({})", name, algorithmId);
    }

    private void seedDefaultAdmin() {
        Long existing = userMapper.selectCount(new LambdaQueryWrapper<User>()
            .eq(User::getUsername, adminUsername));
        if (existing != null && existing > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setDisplayName(adminDisplayName);
        admin.setRole(UserRoles.ADMIN);
        admin.setEnabled(1);
        userMapper.insert(admin);
        log.info("[SEED] 已初始化默认管理员账号: {} （首次登录后请尽快修改密码）", adminUsername);
    }

    /**
     * 兜底种子：admin 角色授权全部场景、user 角色授权 general 通用查询。
     * 已存在的授权不重复插入；如果 sq_scenario 表为空（未导入种子）则跳过。
     */
    private void seedDefaultRoleScenarios() {
        List<Scenario> allScenarios = scenarioMapper.selectList(null);
        if (allScenarios.isEmpty()) {
            log.warn("[SEED] sq_scenario 表为空，跳过角色-场景授权兜底（请确认已导入 smart_query_seed.sql）");
            return;
        }

        for (Scenario s : allScenarios) {
            ensureGrant(UserRoles.ADMIN, s.getId());
        }

        Scenario general = allScenarios.stream()
            .filter(s -> "general".equals(s.getCode()))
            .findFirst()
            .orElse(null);
        if (general != null) {
            ensureGrant(UserRoles.USER, general.getId());
        }

        log.info("[SEED] 角色-场景授权兜底完成：admin 全部 {} 个场景，user 仅 general",
            allScenarios.size());
    }

    private void ensureGrant(String role, Long scenarioId) {
        Long existing = roleScenarioMapper.selectCount(new LambdaQueryWrapper<RoleScenario>()
            .eq(RoleScenario::getRole, role)
            .eq(RoleScenario::getScenarioId, scenarioId));
        if (existing != null && existing > 0) {
            return;
        }
        RoleScenario rs = new RoleScenario();
        rs.setRole(role);
        rs.setScenarioId(scenarioId);
        roleScenarioMapper.insert(rs);
    }
}
