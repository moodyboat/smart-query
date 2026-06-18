package com.smartquery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceUsageStats {
    private Long dataSourceId;
    private String dataSourceName;
    private Long totalQueries;
    private Long successfulQueries;
    private Long failedQueries;
    private Double successRate;
    private Long lastUsedAt;
    private Long avgQueryTimeMs;

    // 时间范围
    private String timeRange; // daily, weekly, monthly
    private LocalDateTime calculatedAt;
}
