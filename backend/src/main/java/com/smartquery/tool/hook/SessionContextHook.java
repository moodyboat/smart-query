package com.smartquery.tool.hook;

import com.smartquery.datasource.DataSourceManager;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import com.smartquery.tool.LifecycleHook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionContextHook implements LifecycleHook {

    private final DataSourceMapper dataSourceMapper;
    private final DataSourceManager dataSourceManager;

    @org.springframework.beans.factory.annotation.Value("${session-context.table-display-limit:15}")
    private int tableDisplayLimit;

    @Override
    public String name() { return "session-context"; }

    @Override
    public int order() { return 10; }

    @Override
    public String onSessionStart(Map<String, Object> context) {
        Long dsId = context.get("dataSourceId") instanceof Long l ? l : null;
        if (dsId == null) return null;

        DataSource ds = dataSourceMapper.selectById(dsId);
        if (ds == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前数据源信息\n");
        sb.append("- 数据源: ").append(ds.getName()).append(" (ID: ").append(ds.getId()).append(")\n");

        try {
            List<String> tables = dataSourceManager.getJdbcTemplate(dsId).queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
                String.class);
            if (!tables.isEmpty()) {
                sb.append("- 可用表 (").append(tables.size()).append("): ");
                if (tables.size() <= tableDisplayLimit) {
                    sb.append(String.join(", ", tables));
                } else {
                    sb.append(String.join(", ", tables.subList(0, tableDisplayLimit))).append(" ... 等").append(tables.size()).append("张表");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("[SESSION-HOOK] Cannot list tables: {}", e.getMessage());
        }

        return sb.toString();
    }
}
