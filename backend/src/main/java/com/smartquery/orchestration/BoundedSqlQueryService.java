package com.smartquery.orchestration;

import com.smartquery.datasource.DataSourceManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Executes a validated SELECT with per-call JDBC limits without mutating the shared template. */
@Service
public class BoundedSqlQueryService {
    private final DataSourceManager dataSourceManager;

    public BoundedSqlQueryService(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    public List<Map<String, Object>> query(SqlAstPolicyService.SqlAstSpec spec,
                                           Map<String, Object> parameters) {
        JdbcTemplate shared = dataSourceManager.getJdbcTemplate(spec.dataSourceId());
        JdbcTemplate bounded = new JdbcTemplate(shared.getDataSource());
        bounded.setMaxRows(spec.maxRows());
        bounded.setQueryTimeout(spec.timeoutSeconds());
        return new NamedParameterJdbcTemplate(bounded).queryForList(spec.sql(), parameters);
    }
}
