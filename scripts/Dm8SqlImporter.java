import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Imports UTF-8 SQL without disql's physical line-length limitation. */
public final class Dm8SqlImporter {
    private Dm8SqlImporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: Dm8SqlImporter <jdbc-url> <user> <password> <sql-file>");
        }
        Class.forName("dm.jdbc.driver.DmDriver");
        String sql = new String(Files.readAllBytes(Paths.get(args[3])), StandardCharsets.UTF_8);
        List<String> statements = splitStatements(sql);
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2]);
             Statement statement = connection.createStatement()) {
            int completed = 0;
            for (String item : statements) {
                String normalized = item.trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                try {
                    statement.execute(normalized);
                    completed++;
                } catch (SQLException error) {
                    String preview = normalized.replace('\n', ' ');
                    if (preview.length() > 240) {
                        preview = preview.substring(0, 240) + "...";
                    }
                    throw new SQLException("statement " + (completed + 1) + " failed: " + preview, error);
                }
            }
            System.out.println("Imported " + completed + " SQL statements from " + args[3]);
        }
    }

    static List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean escaped = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n') {
                    lineComment = false;
                    current.append(ch);
                }
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!singleQuote && ch == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!singleQuote && ch == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (singleQuote) {
                current.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                } else if (ch == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (ch == '\'') {
                singleQuote = true;
                current.append(ch);
            } else if (ch == ';') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.toString().trim().isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }
}
