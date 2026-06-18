package com.smartquery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceTestResult {
    private boolean success;
    private String message;
    private Long latencyMs;

    // 数据库信息
    private String databaseVersion;
    private String databaseName;
    private String currentSchema;

    // 权限信息
    private PermissionCheck permissions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionCheck {
        private boolean canSelect;
        private boolean canShow;
        private boolean canDescribe;
        private boolean canExplain;
        private List<String> grantedPrivileges;
        private List<String> restrictedOperations;
    }
}
