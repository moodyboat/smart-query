package com.smartquery.util;

import com.smartquery.entity.DataSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 数据库 URL 相关工具：SQLAlchemy 连接 URL 构造 + 日志脱敏。
 * 收敛原散落在 MiningService/MiningPredictionService/PipelineService/PythonExecutor 的 4 份重复构造逻辑。
 * 后续新增数据库类型（如达梦 dm）只需在此处加 case。
 */
public final class DbUrlUtil {

    private DbUrlUtil() {
    }

    /**
     * 构造 Python SQLAlchemy 连接 URL（user/pass 做 URLEncoder 编码）。
     */
    public static String buildSqlalchemyUrl(DataSource ds) {
        String user = URLEncoder.encode(ds.getUsername(), StandardCharsets.UTF_8);
        String pass = URLEncoder.encode(ds.getPassword(), StandardCharsets.UTF_8);
        String type = ds.getType() != null ? ds.getType().toLowerCase() : "mysql";
        String driver = switch (type) {
            case "postgresql" -> "postgresql+psycopg2";
            default -> "mysql+pymysql";
        };
        return "%s://%s:%s@%s:%d/%s".formatted(driver, user, pass, ds.getHost(), ds.getPort(), ds.getDatabaseName());
    }

    /**
     * 脱敏 URL 中的明文密码（用于日志/脚本预览）。当前覆盖 mysql+pymysql 协议。
     * TODO（达梦迁移）：DM 接入后扩展正则以覆盖 dm+dmPython:// 等协议。
     */
    public static String maskPassword(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("mysql\\+pymysql://([^:]+):[^@]+@", "mysql+pymysql://$1:***@");
    }
}
