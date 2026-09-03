package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.BoundedSqlQueryService;
import com.smartquery.orchestration.ContentHashService;
import com.smartquery.orchestration.OperatorTypes;
import com.smartquery.orchestration.SqlAstPolicyService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes one immutable, authorized SELECT and turns rows into provenance envelopes. */
@Component
public class SqlAstOperatorExecutor implements OperatorExecutor {
    private final SqlAstPolicyService policyService;
    private final BoundedSqlQueryService queryService;
    private final ContentHashService contentHashService;

    public SqlAstOperatorExecutor(SqlAstPolicyService policyService,
                                  BoundedSqlQueryService queryService,
                                  ContentHashService contentHashService) {
        this.policyService = policyService;
        this.queryService = queryService;
        this.contentHashService = contentHashService;
    }

    @Override
    public String implementationType() {
        return "SQL_AST";
    }

    @Override
    public OperatorExecutionResult execute(OperatorExecutionContext context) {
        if (!OperatorTypes.DATA.equals(context.operatorType())) {
            throw new BusinessException(422, "SQL_AST执行器只能运行DATA算子");
        }
        if (context.upstream() != null && !context.upstream().isEmpty()) {
            throw new BusinessException(422, "SQL_AST数据算子必须是DAG入口节点");
        }
        SqlAstPolicyService.SqlAstSpec spec = policyService.validate(context.implementationPayload());
        Map<String, Object> parameters = policyService.parameters(spec, context.nodeConfig(), context.runInput());
        List<Map<String, Object>> rows = queryService.query(spec, parameters);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : rows) records.add(envelope(context, spec, row));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("records", List.copyOf(records));
        output.put("recordCount", records.size());
        output.put("dataSourceId", spec.dataSourceId());
        output.put("tables", spec.usedTables().stream().sorted().toList());
        return new OperatorExecutionResult(output, List.of(),
            "Authorized SQL_AST SELECT; dataSource=" + spec.dataSourceId()
                + " tables=" + spec.usedTables() + " rows=" + records.size());
    }

    private Map<String, Object> envelope(OperatorExecutionContext context,
                                         SqlAstPolicyService.SqlAstSpec spec,
                                         Map<String, Object> source) {
        Map<String, Object> snapshot = new LinkedHashMap<>(source);
        Map<String, Object> record = new LinkedHashMap<>(snapshot);
        Map<String, Object> identity = new LinkedHashMap<>();
        if (spec.sourceRefFields().isEmpty()) identity.putAll(snapshot);
        else spec.sourceRefFields().forEach(field -> identity.put(field, lookup(snapshot, field)));
        String hash = contentHashService.sha256(identity).substring(0, 20);
        String tables = String.join("+", spec.usedTables().stream().sorted().toList());
        record.put(LineageSupport.SOURCE_REFS,
            List.of("datasource:" + spec.dataSourceId() + ":tables:" + tables + ":record:" + hash));
        record.put(LineageSupport.SOURCE_SNAPSHOTS,
            List.of(Collections.unmodifiableMap(new LinkedHashMap<>(snapshot))));
        record.put(LineageSupport.EVIDENCE, List.of(Map.of(
            "kind", "SQL_QUERY",
            "name", context.nodeId(),
            "dataSourceId", spec.dataSourceId(),
            "tables", spec.usedTables().stream().sorted().toList())));
        return record;
    }

    private Object lookup(Map<String, Object> source, String path) {
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) return null;
            current = map.get(segment);
        }
        return current;
    }
}
