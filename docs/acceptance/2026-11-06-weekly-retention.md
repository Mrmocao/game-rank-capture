# 周报每周唯一验收（2026-11-06）

## 完成

- 新增 Flyway V23：按北京时间自然周周一 `week_start` 唯一；同周只保留一份完整报告。
- 同周再次生成：输入未变化复用；输入变化原地更新 `weekly_report` 与 `report_item`，报告 ID 不变，`revision` 递增。
- 迁移历史重复报告：35 份归并为 2 份（周一为 2026-11-02、2026-10-26），保留各周最近生成记录；旧 ID 通过 `weekly_report_alias` 打开当前周报告。
- 历史抽屉展示所属周、分析区间和最近更新时间；页面明确“每周一份”。
- 采集、榜单快照、产品身份、规则事件等非报告表原样保留。

## 证据

- `mvn -q package`：通过；221 项测试，0 失败、0 错误、26 个环境集成测试按条件跳过。
- `RUN_WEEKLY_RETENTION_MYSQL_TESTS=true mvn -q -pl backend -am -Dtest=WeeklyReportRetentionMySqlTest,WeeklyReportWeekTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过；临时 MySQL 库验证迁移、旧链接、同周覆盖、重复复用、跨周新增、失败回滚、并发生成。
- `npm --prefix frontend run build`：通过；保留现有大包提示。
- 线上迁移前 JDBC 备份：`/opt/iaa-radar/backups/before-weekly-retention-20261106-r2.sql`，65 表 38,688 行。
- V23 后 JDBC 指纹校验：2 个周报告赢家 `[15,35]`、62 张非报告业务表未变；重复周数为 0，别名无悬空引用。
- 线上 Playwright（Chrome，桌面 1440×1000、移动 390×844）：旧报告 #23 跳转到 #35；历史 2 行；连续真实生成两次均为 #35（第一次更新序号 3，第二次复用）；移动端无横向溢出、无运行时错误。

## 使用

选择任意分析截止日点击“生成本周精选”。同一自然周再次点击会更新同一行；历史周报只显示每周一行。二次分析使用 `weekStart`、固定 `reportId`、`revision`、`inputSha256` 和 `availableCutoff`。

## 边界

报告覆盖历史内容被覆盖后不再作为周报历史读取；如需保留某次分析，先导出完整 JSON。原始榜单和采集材料仍持续保存。执行部署前应继续使用 JDBC 备份和迁移后指纹核对。
