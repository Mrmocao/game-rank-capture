# Firecrawl自建运行包

上游参考：Firecrawl v2.11.0 Compose与v2 Scrape协议；官方未提供对应版本tag的全部镜像，故API、Playwright、NuQ使用2026-11-04实际读取的不可变OCI digest。需完成本机环境真实试抓后才能标记可用，不将文件存在当作部署成功。

准备独立目录`/opt/iaa-firecrawl`，把compose.yaml放入目录。最低先为现有应用与本服务预留资源：最初2核8GB、MemAvailable约1.6GB不足；2026-11-04已实际检查升级为4核16GB。本配置单并发、容器总内存上限约5.7GB，不启用LLM抽取或网站全量爬取。

主机安装Docker Engine和Compose插件后，在该目录生成权限600的`.env`，仅包含独立随机`POSTGRES_PASSWORD`。不要复用业务数据库密码。执行：

```sh
python scripts/firecrawl/prepare-images.py --output data/runtime/firecrawl-images
# 把redis.tar/rabbitmq.tar及对应.json传至服务器，核对archiveSha256后导入：
docker load -i redis.tar
docker load -i rabbitmq.tar
# Redis/RabbitMQ的imageId已固定于compose.yaml；核心镜像使用官方digest的镜像通道。
docker compose pull api playwright-service nuq-postgres
docker compose up -d
docker compose ps
docker stats --no-stream
curl --max-time 90 -sS http://127.0.0.1:3002/v2/scrape \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/","formats":["rawHtml"],"maxAge":0,"storeInCache":false}'
```

API只绑定127.0.0.1:3002；依赖无宿主端口，不向公网开放Firecrawl、Redis、RabbitMQ或PostgreSQL。`USE_DB_AUTHENTICATION=false`仅适用于此受信内部入口。应用/Worker同时配置：

```dotenv
CAPTURE_FIRECRAWL_INTERNAL_URL=http://127.0.0.1:3002/v2
CAPTURE_TOOL_ENCRYPTION_KEY=独立随机长密钥
```

工作台“在线规则 / Firecrawl”里保存该地址、无需Key、启用；自建API与地址必须真实可达。仅此服务端环境明确授权的内部入口可用，页面不能任意配置内网URL。公开抓取目标仍必须是公开HTTPS地址。

首次升级部署引擎后，服务配置、URL、字段CSS、属性、等待、数量、发布版本和定时范围均在数据库中即时维护；程序引擎或依赖升级仍需部署。现有引力引擎登录profile不上传Firecrawl，Firecrawl不能替代其登录与完整榜单校验。

运维：卷存储队列/缓存，业务证据保存在应用的CAPTURE_MATERIAL_DIR及MySQL采集表，二者共同备份。升级先停派新网页任务并等待正在运行结束，备份卷/数据库/证据，更新固定digest后试抓并检查内存。停服务可`docker compose stop`，不要用`down -v`删除持久卷。健康检查及容器restart负责进程恢复；定期确认实际试抓而不只看容器running。

出站访问需保留上游的私网目标保护，业务入口也有公开URL/DNS校验。后续如果开放多用户或公开API，需要独立鉴权与请求配额；目前沿用当前工作区访问控制，不额外开放供应商接口。

2026-11-04服务器到GHCR/Docker Hub直连不稳定。核心3镜像通过ghcr.nju.edu.cn取得，manifest digest与官方逐项一致；Redis/RabbitMQ由prepare-images.py从官方源下载、逐层SHA256验证并制成Docker archive，经SSH送达再次核对archiveSha256后docker load。Compose用固定image ID引用已导入依赖，不自动拉取浮动tag。镜像通道不可用时可将核心地址换回ghcr.io，但必须保留相同digest。
