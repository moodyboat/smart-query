import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Prints exact table row counts for one DM8 schema as TABLE=COUNT. */
public final class Dm8TableCounts {
    private Dm8TableCounts() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: Dm8TableCounts <jdbc-url> <user> <password> <schema>");
        }
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2]);
             Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = '" + args[3].toUpperCase() + "'")) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
            Collections.sort(tables);
            for (String table : tables) {
                try (ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + args[3] + ".\"" + table.replace("\"", "\"\"") + "\"")) {
                    result.next();
                    System.out.println(table.toLowerCase() + "=" + result.getLong(1));
                }
            }
        }
    }
}
