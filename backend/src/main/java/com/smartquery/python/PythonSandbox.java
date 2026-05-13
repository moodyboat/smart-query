package com.smartquery.python;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Python 代码安全校验 — 参考 Claude Code BashTool sandbox 模式
 *
 * <p>在代码写入文件执行前进行静态分析，阻止危险操作。
 */
public final class PythonSandbox {

    private PythonSandbox() {}

    private static final Set<String> BLOCKED_IMPORTS = Set.of(
        "subprocess", "ctypes",
        "socket", "http.server", "ftplib", "smtplib", "telnetlib",
        "pickle", "marshal", "importlib", "signal",
        "multiprocessing", "threading"
    );

    private static final Set<String> AUTO_INJECTED = Set.of(
        "pandas", "numpy", "matplotlib", "json", "os", "sys"
    );

    private static final Set<Pattern> BLOCKED_PATTERNS = Set.of(
        Pattern.compile("\\bos\\.system\\b"),
        Pattern.compile("\\bos\\.popen\\b"),
        Pattern.compile("\\bos\\.exec"),
        Pattern.compile("\\bos\\.spawn"),
        Pattern.compile("\\bos\\.remove\\b"),
        Pattern.compile("\\bos\\.unlink\\b"),
        Pattern.compile("\\bos\\.rmdir\\b"),
        Pattern.compile("\\bshutil\\.rmtree\\b"),
        Pattern.compile("\\bsubprocess\\."),
        Pattern.compile("(?<![.\\w])eval\\s*\\("),
        Pattern.compile("(?<![.\\w])exec\\s*\\("),
        Pattern.compile("\\b__import__\\s*\\("),
        Pattern.compile("\\bsys\\.exit\\b"),
        Pattern.compile("\\bos\\.listdir\\b(?!.*_workspace)"),
        Pattern.compile("\\bos\\.walk\\b")
    );

    /**
     * 校验代码安全性。通过则返回原代码，否则抛出 SecurityException。
     */
    public static String validate(String code) {
        if (code == null || code.isBlank()) return code;
        checkImports(code);
        checkPatterns(code);
        return code;
    }

    private static void checkImports(String code) {
        for (String line : code.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("import ")) {
                checkImportStatement(trimmed.substring(7));
            } else if (trimmed.startsWith("from ")) {
                String rest = trimmed.substring(5);
                String module = rest.split("\\s+")[0];
                checkModule(module);
            }
        }
    }

    private static void checkImportStatement(String rest) {
        for (String part : rest.split(",")) {
            String module = part.trim().split("\\s+")[0].split("\\.")[0];
            checkModule(module);
        }
    }

    private static void checkModule(String module) {
        if (module == null || module.isEmpty()) return;
        String base = module.split("\\.")[0];
        // Auto-injected modules are allowed — Python handles duplicate imports gracefully
        if (AUTO_INJECTED.contains(base)) {
            return;
        }
        if (BLOCKED_IMPORTS.contains(base)) {
            throw new SecurityException("禁止导入模块: " + base);
        }
    }

    private static void checkPatterns(String code) {
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(code).find()) {
                throw new SecurityException("代码包含禁止的操作: " + pattern.pattern());
            }
        }
    }
}
