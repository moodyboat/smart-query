package com.smartquery.util;

import com.smartquery.entity.DataSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 数据库 URL 相关工具：SQLAlchemy 连接 URL 构造。
 * 收敛原散落在 MiningService/MiningPredictionService/PipelineService/PythonExecutor 的 4 份重复构造逻辑。
 * 后续新增数据库类型（如达梦 dm）只需在此处加 case。
 */
public final class DbUrlUtil {

    private DbUrlUtil() {
    }

    /**
     * 构造 Python SQLAlchemy 连接 URL（user/pass 做 URLEncoder 编码）。
     * 业务库类型分支：mysql / postgresql / dm（达梦，需 dmPython 包）/ gbase。
     */
    public static String buildSqlalchemyUrl(DataSource ds) {
        String user = URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8);
        String type = ds.getType() != null ? ds.getType().toLowerCase() : "mysql";
        String driver = switch (type) {
            case "postgresql" -> "postgresql+psycopg2";
            case "dm" -> "dm+dmPython";
            default -> "mysql+pymysql";
        };
        return "%s://%s:%s@%s:%d/%s".formatted(driver, user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
    }
}
