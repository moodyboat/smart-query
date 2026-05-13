package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.tool.ToolRegistry;
import com.smartquery.tool.ToolResult;
import com.smartquery.tool.ToolExecutionContext;
import com.smartquery.tool.impl.ExecuteSqlTool;
import com.smartquery.tool.impl.SchemaExploreTool;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tool")
@RequiredArgsConstructor
public class ToolTestController {

    private final SchemaExploreTool schemaExploreTool;
    private final ExecuteSqlTool executeSqlTool;
    private final ToolRegistry toolRegistry;

    @PostMapping("/schema-explore")
    public Result<ToolResult> schemaExplore(
        @RequestParam Long dataSourceId,
        @RequestBody Map<String, Object> input
    ) {
        ToolExecutionContext ctx = new ToolExecutionContext(
            0L, dataSourceId, "test", "test", () -> false, null);
        ToolResult result = schemaExploreTool.execute(input, ctx);
        return Result.ok(result);
    }

    @PostMapping("/execute-sql")
    public Result<ToolResult> executeSql(
        @RequestParam Long dataSourceId,
        @RequestBody Map<String, String> body
    ) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("sql", body.get("sql"));
        input.put("data_source_id", dataSourceId);
        ToolExecutionContext ctx = new ToolExecutionContext(
            0L, dataSourceId, "test", "test", () -> false, null);
        ToolResult result = executeSqlTool.execute(input, ctx);
        return Result.ok(result);
    }

    @GetMapping("/definitions")
    public Result<List<Map<String, Object>>> getToolDefinitions() {
        return Result.ok(toolRegistry.getToolDefinitions());
    }
}
