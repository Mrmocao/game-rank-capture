# 服务器采集与登录续期维护

当前生产采集已运行在14.103.231.1的AlmaLinux8.10，不依赖个人Windows电脑或Codex在线，不需要Docker。Windows原采集/同步常驻进程和启动项已关闭；本地工具仅供临时维护。

## 已部署结构

- Java21 + Playwright1.58.0 + Google Chrome156.0.8078.79。Chrome RPM从Google官方HTTPS地址下载，导入官方签名密钥后rpm --checksig通过；包SHA256为0399d98f1ee16eccb39fb14a3461b25b2d5ae093f96a9b585cfb54f0a6a67c1a。中文字体、Xvnc/noVNC仅用于人工登录窗口。
- `iaa-radar-capture.service`：非root的iaa-radar用户运行Java采集worker，读取生产数据库中的计划，每分钟检查到期时间；当前每天北京时间08:30、23:00。串行浏览器、1GB内存上限、进程异常自动重启、开机启动。
- `iaa-radar-capture-reports.service`：服务器本机检查Java原子清单，成功资料已由Java通过JDBC写入；此桥不重复解析/入库，只生成或复用冻结周报，保存完成回执与登录状态。报告失败可重试。
- `/opt/iaa-radar/shared/browser-sessions/gravity`：服务器独立浏览器会话，权限700。用户已在服务器浏览器手动登录，从未复制本机Chrome/Cookie。
- `/opt/iaa-radar/shared/deliveries`：heartbeat.json、latest.json、source-session.json、各批回执和server-state.json。后端读取公开状态，不返回凭据。

`CAPTURE_WORKER_ENABLED=false`关闭Web进程内的重复执行器；独立systemd服务运行计划。CAPTURE_BROWSER_PROFILE_DIR指向上述服务器目录，CAPTURE_MATERIAL_DIR使用shared/external-captures，CAPTURE_SYNC_RECEIPT_DIR使用shared/deliveries。

worker通过systemd的EnvironmentFile读取服务器配置，CLI的--env-file指向不含密钥的shared/capture-worker.env以定位可写日志目录；不要把带密码的环境文件source进Shell。数据库仍通过JDBC访问127.0.0.1:13306。服务单元在scripts/systemd/，调整后systemctl daemon-reload并重启对应服务。

## 7天登录维护机制

7天有效期来自源站登录选项，不是系统能够承诺的永久会话。系统不破解验证码、不复制其他设备凭据、不自动购买或续费。

1. 只有实际微信3榜、抖音4榜全部SUCCEEDED且各100条，才记录服务器会话验证通过。
2. 首次验证或人工维护后的首次完整验证记录lastLoginVerifiedAt，并安排第6天reviewAfter。正常日常抓取只更新lastValidatedAt，**不会每天把续登提醒顺延**。
3. 真实采集返回SOURCE_AUTH_REQUIRED时立即标记LOGIN_REQUIRED；不等到第7天，不把网络/解析故障冒充登录过期。
4. “信息抓取”页显示最近登录验证、建议检查时间和续登说明。首页在第6天或实际失效时出现提醒。当前提醒为后台页面状态，无外发短信/邮件。
5. 来源会话失效不清空历史，不补假数据；Apple等可用来源继续，周报按实际覆盖生成或复用。登录过期必须由有权限的人再次完成短信/扫码。

建议时间不是读取Cookie所得的准确到期时间。若源站主动注销、设备验证或风控提前触发，会按实际失败状态提示。

## 用户续登：只在需要时运行维护工具

当前运维电脑已具备Python3/paramiko4.0.0和忽略目录中的SSH配置。无需重新填写账号密码到业务页面。

```powershell
# 在项目目录执行；工具只在维护期间运行，无开机常驻。
powershell -ExecutionPolicy Bypass -File scripts/renew-server-capture-login.ps1
```

工具经固定主机指纹SSH连接服务器，暂停采集并打开临时登录窗口；默认浏览器访问localhost的隧道入口。用户在远程浏览器中完成源站登录，然后回到工具按回车。工具保存会话、关闭登录窗口、真实采集验证、恢复后台计划并关闭本地隧道；之后可关闭个人电脑。

不要把源站密码或验证码填写到聊天、Git或.env。`data/deployment/capture-sync.json`是已有SSH运维配置（不是持续同步服务），不提交仓库。其它运维机器可按`scripts/capture-sync.config.example.json`配置密钥与核验过的主机指纹；只在续登时启动工具。

远程登录服务仅监听服务器127.0.0.1:6081/5991，不开放公网远程桌面。临时SSH查看端口仅监听运维电脑127.0.0.1:29881。登录窗口最长30分钟，显示服务最长35分钟；35分钟看门狗清理窗口并恢复采集，防止取消维护导致计划永久暂停。正常结束立即清理。

服务器管理员也可运行：

```bash
bash /opt/iaa-radar/shared/server-capture-login.sh start
# 使用临时SSH隧道登录后
bash /opt/iaa-radar/shared/server-capture-login.sh finish
```

finish会立即实采，不能仅凭“窗口已关闭”宣称登录成功；失败保留实际错误且仍恢复定时器。网站变动导致适配器失败时须修复代码并回归，不应反复重新登录掩盖问题。

## 运维检查与升级

```bash
systemctl status iaa-radar-capture iaa-radar-capture-reports
journalctl -u iaa-radar-capture-reports --since today
```

Java按日日志位于shared/data/worker-logs；Web“信息抓取”页显示生产计划心跳、下次执行、来源错误和修复记录。上新JAR前停止独立采集worker，切换不可变release，重启Web与worker；不在正在抓取的JAR上覆盖构建。备份数据库、原始材料、登录profile时按敏感配置保护，恢复到另一机器应重新登录验证。

目前Chrome固定为已安装验收版本，更新浏览器或Playwright后需重新验证中文页面、登录、日期控件、连续Top100和证据定位。服务器约4GB内存；当前worker空闲约154MB，采集验证通过，未来扩大并发前需重新测量容量。

## 本轮实际验收

服务器独立新采批次72601f3a-2ba5-47dc-9047-4130c13f66c3：微信3榜和抖音4榜各100，Apple96（PARTIAL），合计796；Java实采约34秒，数据库、原始SHA256和DOM定位逐行核验一致。后台自动生成冻结报告11；随后真实维护开关与再次采集生成报告12。报告9/10保留不覆盖。

验证了两个服务enabled/active、计划08:30/23:00、进程重启次数0、固定本地端口限制、维护开关和恢复流程、35分钟清理定时器已登记、两次完整引力采集、浏览器真实状态及第6天/失效状态的合成UI测试（不改生产状态）、390px无页面溢出。6项服务器桥单元测试通过；前后端构建通过。Windows常驻采集/同步进程为0，启动项已删除。

尚未实际等待7天过期、未执行服务器重启演练、未等待自然08:30/23:00长周期运行；因此不能承诺源站永久免登录或长期零故障。35分钟看门狗配置已核验，未专门耗时等待完整35分钟触发。缺少实际IAA/实机证据仍保持待核研究，不因采集部署完成而提升资格。
