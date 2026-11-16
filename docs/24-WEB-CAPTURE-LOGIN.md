# 引力引擎网页续登

入口：全站失效横幅 / 信息抓取 → 打开续登页面。先输入独立维护口令，再打开服务器浏览器。用户完成短信或扫码后点击“已登录，保存并验证”。服务端关闭浏览器保存会话、执行真实采集并恢复原定时服务。

## 状态与提醒

`GET /api/v1/admin/capture-login/status` 直接查询当前环境的采集数据库，不依赖报告桥更新回执。`SOURCE_AUTH_REQUIRED` 产生未恢复事件，后续网络错误和 PARTIAL 不解除提醒，只有对应来源/榜单成功后解除。同一事件保持最早未恢复失败 ID，供通知去重。页面每分钟轮询；打开续登对话框后每4秒刷新。

`scripts/check-server-capture-session.py` 通过已有固定服务器指纹 SSH 读取生产状态，不访问本机 `.env`。Codex 会话提醒只能在自动化执行环境可运行时工作；网页的服务器状态提示独立生效。没有失败事件只表示未收到失败证据，不能证明会话现在有效。

## 部署

1. 安装现有 Chrome、Xvnc、noVNC 和 `server-capture-login.sh`。
2. 执行 `scripts/install-web-capture-login.sh`：安装 root 拥有的固定脚本和3个 systemd oneshot 单元；仅授予应用用户启动这些指定单元的 sudo 权限，不授予任意命令权限。
3. 设置 `CAPTURE_LOGIN_ENABLED=true`、`CAPTURE_LOGIN_PUBLIC_ORIGIN=https://iaa.koi.youkia.net`、随机至少24字符 `CAPTURE_LOGIN_ACCESS_CODE`。口令只保存于服务器环境配置，通过受控方式交付维护人员，不提交仓库。
4. Nginx http 段定义 `$capture_public_scheme`：可信 HTTPS 反代回源的 `X-Forwarded-Proto=https` 映射为 https，否则保留 `$scheme`。API反代传入该值；应用后端只监听127.0.0.1。include `scripts/nginx/capture-login.conf` 到站点内。noVNC 和 VNC 端口始终只绑定回环地址。
5. WAF 域名保持原证书，HTTP 回源 `14.103.231.1:18080`，保留原始 Host、Origin、Cookie、X-Forwarded-Proto，支持 WebSocket Upgrade。生产使用域名 HTTPS，公网 HTTP 地址不能在浏览器中设置或携带 Secure 维护 Cookie。
6. 将该 HTTPS Origin 加入 `APP_CORS_ALLOWED_ORIGINS`，否则 Spring 的跨域过滤会拒绝反代回源上的管理请求。

## 访问边界

管理口令换取30分钟的 Secure/HttpOnly/SameSite=Strict Cookie；每次变更还验证精确 HTTPS Origin。Nginx auth_request 校验所有查看器资源与WebSocket升级；窗口非READY、超时或未授权返回403。WebSocket建立后最长运行由服务器窗口31分钟自动清理约束。对话框关闭不等于完成续登，服务器自动清理保障原采集服务恢复。

首次打开会暂停引力引擎采集；窗口关闭与错误清理均恢复采集。保留原08:30/23:00数据库计划。`CLOSED` 仅意味着流程结束，前端仍以真实采集失败事件决定是否提示失效，不能仅根据脚本退出码声称登录成功。

验证：维护口令限速/有效期、跨站变更拒绝、未授权/过期/非READY查看器拒绝、匿名PARTIAL不误报、连续失败不重复事件、生产采集恢复才关闭事件；离线自动测试不连生产。
