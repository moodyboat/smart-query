package com.smartquery.python;

import java.util.List;

/**
 * Python 执行结果 — 翻译 BashTool ExecResult
 */
public record PythonResult(
    String stdout,
    String stderr,
    int exitCode,
    List<String> artifacts,
    int executionTimeMs
) {
    public static PythonResult success(String stdout, List<String> artifacts, int timeMs) {
        return new PythonResult(stdout, "", 0, artifacts, timeMs);
    }

    public static PythonResult error(String stderr, int exitCode, int timeMs) {
        return new PythonResult("", stderr, exitCode, List.of(), timeMs);
    }

    public static PythonResult timeout(String stderr, int timeMs) {
        return new PythonResult("", stderr, -1, List.of(), timeMs);
    }
}
