package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_data_source")
public class DataSource extends BaseEntity {

    private String name;
    private String type;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;
    private String extraConfig;
    private Integer status;
    private Boolean forQuestionAnswering;

    @TableField(exist = false)
    private Boolean system;

    public String getJdbcUrl() {
        String t = type == null ? "" : type.toLowerCase();
        return switch (t) {
            case "mysql" -> "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000".formatted(host, port, databaseName);
            case "postgresql" -> "jdbc:postgresql://%s:%d/%s?currentSchema=public".formatted(host, port, databaseName);
            case "oracle" -> "jdbc:oracle:thin:@%s:%d:%s".formatted(host, port, databaseName);
            case "dm" -> "jdbc:dm://%s:%d/%s".formatted(host, port, databaseName);
            case "gbase" -> "jdbc:gbase://%s:%d/%s".formatted(host, port, databaseName);
            default -> throw new IllegalArgumentException("Unsupported database type: " + type);
        };
    }

    public String getDriverClassName() {
        String t = type == null ? "" : type.toLowerCase();
        return switch (t) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            case "dm" -> "dm.jdbc.driver.DmDriver";
            case "gbase" -> "com.gbase.jdbc.Driver";
            default -> throw new IllegalArgumentException("Unsupported database type: " + type);
        };
    }
}
