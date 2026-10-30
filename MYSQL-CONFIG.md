# MySQL 连接填写文件

安装 MySQL 8 后，复制 `.env.example` 为 `.env`，只填写下面 3 项即可。应用使用 JDBC，不需要把密码写入代码。

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:3306/iaa_radar?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
DB_USERNAME=iaa
DB_PASSWORD=请填写数据库密码
```

如果数据库不在本机：

```dotenv
DB_URL=jdbc:mysql://服务器IP:端口/iaa_radar?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
```

首次运行前创建数据库和账号（示例）：

```sql
CREATE DATABASE iaa_radar CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'iaa'@'%' IDENTIFIED BY '请替换为强密码';
GRANT ALL PRIVILEGES ON iaa_radar.* TO 'iaa'@'%';
FLUSH PRIVILEGES;
```

当前 `iaa_radar` 已创建，2026-10-30配置库及生产库均迁移至V11；新增表/字段继续使用中文注释。当前不需要再次建库或手动重跑建表脚本；启动后端会通过JDBC应用尚未执行的Flyway增量迁移。配置库3306与生产专用库127.0.0.1:13306相互独立，勿混用报告ID。备份与核验命令见 [MIGRATION-NOTES.md](MIGRATION-NOTES.md)。

如果你的数据库账号拥有建库权限，也可以把 JDBC URL 改成下面这样，让驱动自动建库：

```dotenv
DB_URL=jdbc:mysql://服务器IP:端口/iaa_radar?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
```

生产环境更建议由管理员明确执行 `CREATE DATABASE`，不要给应用账号建库权限。

启动 API 后 Flyway 会自动建表；开发环境使用 `dev` profile 才会写入 `DEMO` 模拟数据。生产环境不要启用该 profile。
