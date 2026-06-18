import java.sql.*;

public class FixLlmConfig {
    public static void main(String[] args) {
        String url = "jdbc:mysql://localhost:3306/smart_query";
        String user = "root";
        String password = "900110";

        try {
            // 加载MySQL驱动
            Class.forName("com.mysql.cj.jdbc.Driver");

            System.out.println("连接数据库...");
            Connection conn = DriverManager.getConnection(url, user, password);

            System.out.println("当前LLM配置:");
            System.out.println("====================");
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(
                "SELECT model_code, api_url, LEFT(api_key, 15) as key_preview, status " +
                "FROM sq_llm_config WHERE deleted=0 AND status=1"
            );

            while (rs.next()) {
                System.out.println("模型: " + rs.getString("model_code"));
                System.out.println("  URL: " + rs.getString("api_url"));
                System.out.println("  Key: " + rs.getString("key_preview") + "...");
                System.out.println("  状态: " + rs.getInt("status"));
                System.out.println();
            }

            System.out.println("修复API URL...");
            System.out.println("====================");
            int updated = stmt.executeUpdate(
                "UPDATE sq_llm_config " +
                "SET api_url = 'https://open.bigmodel.cn/api/coding/paas/v4/chat/completions', updated_at = NOW() " +
                "WHERE model_code IN ('glm-4', 'glm-5.1') AND deleted=0"
            );
            System.out.println("更新了 " + updated + " 条记录");

            System.out.println("\n修复后的配置:");
            System.out.println("====================");
            rs = stmt.executeQuery(
                "SELECT model_code, api_url, status " +
                "FROM sq_llm_config WHERE model_code IN ('glm-4', 'glm-5.1') AND deleted=0"
            );

            while (rs.next()) {
                System.out.println("模型: " + rs.getString("model_code"));
                System.out.println("  URL: " + rs.getString("api_url"));
                System.out.println("  状态: " + rs.getInt("status"));
                System.out.println();
            }

            rs.close();
            stmt.close();
            conn.close();

            System.out.println("✅ 配置修复完成！");

        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
