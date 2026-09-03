package com.smartquery.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowOutputBindingValidationTest {
    @Test
    void outputNodeRequiresPublishedVersionAndImmutableDisplaySpec() {
        List<String> errors = VersionCatalogService.outputNodeErrors(
            "result", VersionStatus.CANDIDATE, true,
            Map.of("contentSpec", Map.of("title", "绕过已发布规格")));

        assertEquals(2, errors.size());
    }

    @Test
    void outputNodeCannotHaveOutgoingEdge() {
        List<String> errors = VersionCatalogService.outputTerminalErrors(Set.of("result"),
            List.of(Map.of("source", "result", "target", "next")));

        assertEquals(List.of("输出节点[result]必须是终点，不能连接下游节点"), errors);
    }

    @Test
    void sqlAstSourceCannotReceiveIncomingEdge() {
        List<String> errors = VersionCatalogService.sourceRootErrors(Set.of("sql_source"),
            List.of(Map.of("source", "input", "target", "sql_source")));

        assertEquals(List.of("SQL_AST数据节点[sql_source]必须是入口，不能接收上游连线"), errors);
    }

    @Test
    void nodeTimeoutMustStayInsideRuntimeBoundary() {
        assertEquals(List.of(), VersionCatalogService.nodeTimeoutErrors(
            "model", Map.of("nodeTimeoutSeconds", 90)));
        assertEquals(1, VersionCatalogService.nodeTimeoutErrors(
            "model", Map.of("nodeTimeoutSeconds", 0)).size());
        assertEquals(1, VersionCatalogService.nodeTimeoutErrors(
            "model", Map.of("nodeTimeoutSeconds", 3601)).size());
    }
}
