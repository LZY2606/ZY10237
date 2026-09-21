# 迁徙轨迹审理台

本地运行的野生动物遥测轨迹审理工具。系统从固定 fixture 导入 GPS 定位、加速度摘要、设备开关机事件、电池状态和设备参数版本，使用 SVG 在本地坐标网格中展示轨迹、误差椭圆、速度和行为状态，不下载地图。

## 安装与演示

安装打包：

```bash
mvn -q -DskipTests package
```

执行自动化测试并启动演示：

```bash
mvn -q test
mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5577
```

访问：

```text
http://127.0.0.1:5577
```

页面应显示“迁徙轨迹审理台”。默认 SQLite 文件位于 `data/migration-review.db`，可用环境变量覆盖：

```bash
REVIEW_DB=/tmp/migration-review.db mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5577
```

## 固定数据与重放

固定数据在 `src/main/resources/fixtures/migration-fixture.json`，包含动物 `A01` 的两个设备代次：

- `G1 / D100 / P-OLD-1`：旧项圈，计划于 `2026-04-05T06:00:00Z` 更换。
- `G2 / D200 / P-NEW-2`：新项圈。
- 旧设备在更换后上传一条迟到记录 `F04`，记录保留并标记为“旧设备迟到记录”。
- 新设备 `F05 → F06` 之间有 20 小时 50 分长缺测。
- `F07` 的误差椭圆坐标为 `GRID-BETA`，轨迹坐标为 `GRID-ALPHA`，相邻边禁止距离判断。
- `F13` 表示动物在连续时间中返回旧位置；`F14` 是后收到且设备时间早于前一条的时钟回跳记录。

应用启动时若数据库为空会自动导入该 fixture。页面上的“清空并重导复核”或 `POST /api/import/reset` 会删除业务表数据后重新导入同一 fixture，同时记录一次 `RESET_AND_REIMPORT` 操作。

## 审理口径

- 跨项圈 / 跨代次只创建 `CROSS_GENERATION` 隔断：不计算距离、不计算速度、不画轨迹直线。
- 同一代次内超过 6 小时无有效定位时创建 `LONG_GAP` 隔断；两端不连线，窗口时长以下界和上界两个字段展示。
- 误差椭圆 CRS 与轨迹 CRS 不一致，或边的两个误差 CRS 不一致时，创建阻断边：距离、速度和空间行为判定均为空。
- 后收到记录的设备时间早于同设备前一次收到的设备时间，标记为时钟回跳；该边为 `CLOCK_ANOMALY`。
- 连续时间、同代次且误差坐标可用的重复到访位置标记为“动物返回旧位置”，不与时钟回跳混淆。
- 拒绝低质量点后，该点不参与轨迹；若两个剩余观测之间存在被拒绝点，生成 `REJECTED_GAP` 隔断。
- 每个行为分段保留原始定位 ID、时间窗、设备、代次、参数版本和导入批次。

## 两个状态序列候选

页面同时生成并默认保留两套候选：

- `CANDIDATE-A-ACCEL`：依据加速度活动指数，活动值 `<30` 为停留，`>=40` 为移动，其余为不确定。
- `CANDIDATE-B-SPATIAL`：依据同坐标系统下的速度与停留阈值；CRS 不符、长缺测、时钟回跳、跨代次均输出不确定或结构隔断。

可以在页面调整某个分段的状态，也可以调用 `POST /api/segments/retain-both` 明确保留两个候选。人工调整写入 `segment_overrides`，每次审理动作写入 `review_actions`。

## 主要 API

- `GET /api/case`：完整审理视图。
- `POST /api/fixes/{id}/reject`：拒绝低质量定位。
- `POST /api/fixes/{id}/accept`：恢复采用定位。
- `POST /api/generations/{id}/confirm`：确认设备更换边界。
- `POST /api/segments/adjust`：调整行为分段状态。
- `POST /api/segments/retain-both`：保留两个状态序列候选。
- `POST /api/import/reset`：清空数据库并重放固定 fixture。

## 运行记录与导出

页面右上角“导出运行记录 JSON”会下载当前完整 `/api/case` 响应，包括定位、边、分段、参数版本、设备事件、人工操作、保留候选和阻断原因。导出文件不依赖外部地图服务。

## 数据存储

SQLite schema 在 `src/main/resources/schema.sql`，核心表包括：

- `telemetry_fixes`：定位、质量、误差椭圆、加速度、参数版本和迟到 / 时钟标记。
- `device_events`：开关机和电池摘要。
- `device_generations`：动物的设备代次和更换边界确认状态。
- `parameter_versions`：设备参数版本和配置快照。
- `segment_overrides`：人工行为分段调整。
- `review_actions`：可导出的审理操作记录。
- `import_runs`：fixture 重放批次。

## 自动化测试

`src/test/java/local/telemetry/review/ReviewApplicationTests.java` 覆盖：

- 页面标题。
- 跨代速度阻断。
- 长缺测不画直线及时长上下界。
- 误差 CRS 不符时拒绝距离判断。
- 时钟回跳与真实返回旧位置区分。
- 拒绝低质量点后生成受保护缺口。
- 确认设备边界、人工调整分段并保留两个候选。
- 清空数据库后重新导入固定 fixture。
- 行为分段追溯原始定位、代次、设备、参数版本和导入批次。
