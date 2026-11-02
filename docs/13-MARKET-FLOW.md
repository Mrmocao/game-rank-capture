# 来源采集到市场研究的当前流程

适用于2026-11-02起的单一当前设计。Java21、MySQL8、JDBC/Flyway、Vue；不需要Docker或MySQL CLI。

## 来源与可见数据

- 微信：https://rank.gravity-engine.com/wechat/day/；人气、畅销、畅玩3榜。
- 抖音：https://rank.gravity-engine.com/douyin/day/?date=2026-11-02；热门、畅销、新游、发行人热度4榜。指定日期由真实页面日期控件核验。
- Apple中国游戏免费RSS；无历史选择，不补造历史。
- 引力榜是第三方口径；Top100依赖本机已登录专用浏览器。海外未覆盖不阻断国内。

概览显示当日真实榜单和条数、最近同步回执与心跳；榜单观察展示各榜独立名次；产品情报库默认展示来源候选、缩略图、玩法依据。旧的已关联身份档案在另一页签；来源候选不伪造平台ID，也不凭名字跨平台合并。

候选证据抽屉显示7日名次、缺日、观察ID、原文SHA256及DOM定位。描述中的明确玩法由确定性规则提取，标签或原句随结果返回；来源宣称的DAU、GMV不作为核实数值。来源候选可由多人分别填写研究状态和备注，记录冻结当时来源，不会提升IAA资格。

## 运行与安装

本地`.env`配置JDBC和CAPTURE_BROWSER_PROFILE_DIR；生产`/etc/iaa-radar/app.env`配置生产JDBC，两者是独立库（当前3306与13306），不要互换。先备份再迁移：

```powershell
mvn package
./scripts/run-ranking-capture.ps1 -Migrate
./scripts/login-ranking-capture.ps1
./scripts/install-capture-worker.ps1
```

已安装的Java独立执行器读取数据库计划：08:30、23:00（北京时间）。任意计划/UI/CLI运行均原子生成`CAPTURE_MATERIAL_DIR/batch-UUID.json`；失败也交付状态，半写入`.pending`不会发送。电脑须开机、Windows用户已登录、网络与来源会话有效；登录过期保持失败并重新手动登录。

服务器安装`scripts/receive-capture.py`到`/opt/iaa-radar/shared/receive-capture.py`，创建shared/deliveries和shared/capture-upload目录，设置`CAPTURE_SYNC_RECEIPT_DIR=/opt/iaa-radar/shared/deliveries`。当前服务器保持免登录、CAPTURE_WORKER_ENABLED=false，浏览器Cookie不转移。

复制`scripts/capture-sync.config.example.json`为忽略目录`data/deployment/capture-sync.json`，填写SSH主机、端口、部署用户、私钥文件和经核验的主机公钥SHA256十六进制值、绝对materialRoot；也支持password字段，但不要提交或打印。依赖已锁定paramiko4.0.0。当前机器配置已完成，无需重复填写。

```powershell
python -m pip install -r scripts/capture-sync-requirements.txt
# 仅首次安装、已核对旧批次全部处理后，标记基线。不要用此命令跳过待交付数据。
python scripts/sync-capture-server.py --config data/deployment/capture-sync.json --initialize
./scripts/install-capture-sync.ps1
```

同步进程使用当前用户登录启动项，每60秒检查新清单；启动器隐藏运行窗口，文件锁阻止多个同步进程。只发送显式列出的bundle.json/raw.bin和脱敏清单，不传浏览器目录、Cookie或.env。SSH主机指纹不一致时拒绝连接。

## 服务端接收与重试

接收器只允许预期ZIP成员，限制大小并核验原始哈希；使用服务器自己持有的JDBC环境文件调用Java重解析，不直接信任客户端声明进行业务写入。不同来源可有不同业务日期，按原日期入库；一次新批生成最新来源日所在周的报告，历史观察可供7日研究窗口使用。

幂等层次：同批次源清单哈希复用回执；同材料同范围复用来源观察；同报告输入复用报告。每次尝试日志仍保存。部分覆盖不借旧行凑Top100；来源失败不生成零条成功快照；已入库但报告失败保留INGESTED_REPORT_FAILED供下轮重试。

查看本地`data/deployment/capture-sync.log`与capture-sync-state.json、data/worker-logs；查看服务器shared/deliveries/latest.json、heartbeat.json和对应批次JSON。概览超过5分钟无心跳提示未上报。源站会话过期运行登录脚本；SSH故障修复配置后自动重试。人工重放指定批次时先停止匹配当前配置的同步进程，避免文件锁冲突：

```powershell
python scripts/sync-capture-server.py --config data/deployment/capture-sync.json --batch data/external-captures/batch-<UUID>.json
./scripts/install-capture-sync.ps1
```

不删除数据库历史或source材料修复失败。相同批次已完成回执直接复用；如规则更新需要新报告，使用页面“生成本周精选”，不覆盖旧回执。

## API与冻结规则

| 接口 | 作用 |
|---|---|
| GET /api/v1/market/coverage?date=YYYY-MM-DD | 固定8榜定义、真实覆盖和同步状态 |
| GET /api/v1/market/rankings?date=...&chartId=capture:... | 单榜当日及可追溯历史，不回填 |
| GET /api/v1/market/candidates?date=...&platform=DOUYIN&q=... | 同来源候选，独立平台与榜单 |
| GET /api/v1/market/selections | 当前组织最新200条来源研究记录 |
| POST /api/v1/market/selections | owner/status/note/observationId/rank，Idempotency-Key必填；冲突409 |
| POST /api/v1/admin/reports/generate?periodStart=...&periodEnd=... | 左闭右开，冻结报告或复用 |

研究规则capture-research-leads-1.2.0：至少5/7日且最近3日连续、当日Top20热度或提升至少10名且进入Top50、排除畅销风险、每玩法最多2项，总数不超过5。缺稳定身份时只能称来源标签名次变化。每条保存理由、反面信号、缺口和下一步；旧报告不读实时数据重新计算。国内没有合格候选也不凑数。

## 验证边界

真实验证为源站可见材料、服务器重解析、JDBC入库、自动生成报告、重复交付幂等、后台自动发现与回执、浏览器只读全流程。研究选用UI写入使用显式SYNTHETIC响应，实际写入/双负责人/冲突/冻结通过JDBC事务回滚验证，不留生产测试记录。自然计划长时间运行、完整灾难恢复、真实AI效果、版本级IAA、商业保存/再分发协议尚未验证。
