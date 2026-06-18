# IDEA 运行配置说明

## 手动创建运行配置

### 步骤 1：打开运行配置
1. 点击 IDEA 顶部菜单：`Run` → `Edit Configurations...`
2. 点击左上角的 `+` 号
3. 选择 `Spring Boot`

### 步骤 2：配置参数
**Basic 配置：**
- **Name**: `MetricApplication`
- **Main class**: `com.gdgp.metric.MetricApplication`
- **Module**: `gdgp-metric-provider`
- **Package**: `com.gdgp.metric`

**Configuration 配置：**
- **Active profiles**: `local`
- **VM options**: `-Dspring.profiles.active=local`

**Environment 配置：**
```
DB_URL=jdbc:mysql://localhost:3306/ads?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai
DB_USERNAME=root
DB_PASSWORD=900110
DB_DRIVER=com.mysql.cj.jdbc.Driver
```

### 步骤 3：保存并运行
1. 点击 `OK` 保存配置
2. 在右上角选择 `MetricApplication` 配置
3. 点击绿色运行按钮（▶️）

## 快捷方法

### 使用运行按钮
1. 打开 `MetricApplication.java` 文件
2. 查看编辑器左侧是否有绿色运行小箭头
3. 如果有，点击 `main` 方法旁边的箭头
4. 选择 `Run 'MetricApplication.main()'`

### 使用右键菜单
1. 在 `MetricApplication.java` 文件上右键
2. 选择 `Run 'MetricApplication.main()'`

## 如果仍然无法运行

### 检查项目结构
1. 打开 `File` → `Project Structure` (Ctrl+Alt+Shift+S)
2. 确认 `gdgp-metric-provider` 被识别为模块
3. 确认 `src/main/java` 被标记为 Sources Root

### 重新导入项目
1. 关闭 IDEA 中的项目
2. 删除项目目录下的 `.idea` 文件夹
3. 重新打开 IDEA
4. 选择 `File` → `Open` → 选择项目目录
5. 选择 `Open as Project`

### 刷新 Maven
1. 打开 Maven 面板
2. 点击刷新按钮（🔄）
3. 等待依赖下载完成

## 验证配置

运行成功后，控制台应该显示：
```
Started MetricApplication in XX seconds
Tomcat started on port 21050
```

然后可以访问：
- 指标引擎前端：http://localhost:5174
- API文档：http://localhost:21050/api/swagger-ui.html