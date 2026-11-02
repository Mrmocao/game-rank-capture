# 持久登录与完整榜单传输

本模块只实现浏览器会话与滚动取材，依赖 `external-capture` 契约，不引用后端、数据库或业务服务。后端通过 `MaterialFetcher` 接口选择该传输。公开 Apple RSS 仍使用 HTTP。

## 当前生产部署

浏览器传输支持Linux服务器。生产已安装Chrome并使用服务器独立profile，systemd执行采集，用户电脑不承担后台。初次/到期登录通过临时SSH远程窗口完成；第6天检查提示、实际失效告警和一键维护见[服务器运行与续登说明](../docs/14-SERVER-CAPTURE-OPERATIONS.md)。以下PowerShell登录命令适用于开发机手动验证，不是当前生产启动方式。

## 开发机初次登录

在项目根目录 `.env` 配置固定绝对路径：

```dotenv
CAPTURE_BROWSER_PROFILE_DIR=D:/project/game-rank-capture/data/browser-sessions/gravity
CAPTURE_BROWSER_CHANNEL=chrome
CAPTURE_BROWSER_HEADLESS=true
```

```powershell
./scripts/login-ranking-capture.ps1
```

需要本机已安装 Chrome。脚本构建后打开专用窗口，由用户手动登录引力引擎；可以选择网站提供的“7天免登录”。登录完成后关闭该专用窗口，浏览器保存会话。无需把账号、密码或验证码提供给助手。该目录与用户日常 Chrome 配置隔离，不能同时打开两个采集/登录进程；占用时返回 `BROWSER_PROFILE_BUSY`。

会话仅保存在本机浏览器 profile，不导出 Cookie/storageState 到 JSON 或数据库，不保存用户输入的密码到应用配置。目录在 `.gitignore` 中；当前工作机额外限制为当前用户与SYSTEM访问。网站仍可以主动使会话失效，不能承诺永久免登录。更换机器、操作系统用户或站点会话过期后，应重新手动登录，不复制凭证绕过站点验证。

## 采集和登录失效

```powershell
./scripts/run-ranking-capture.ps1 -Run
./scripts/run-ranking-capture.ps1 -SessionStatus
```

配置浏览器目录后，微信/抖音通过已登录会话加载实际页面，逐榜滚动直到取得100行或停止增长，再由核心核验每榜连续1–100名。看见第100名但中间有缺口也不能通过。

- `SOURCE_AUTH_REQUIRED`：页面要求登录且未取得100名；运行失败并提示重新运行登录脚本。
- `SOURCE_TOP100_INCOMPLETE`：滚动后仍不完整，但没有明确登录提示；保留为不完整故障，不误报会话过期。
- `BROWSER_PROFILE_BUSY`：登录窗口或其他采集持有专用目录，关闭窗口后再执行。
- `BROWSER_START_FAILED` / `BROWSER_CAPTURE_FAILED`：浏览器环境或页面加载失败。

不会在登录失效时静默回退至匿名20条并标记成功。`-SessionStatus`只查询最近运行，不触发登录或联网；没有运行时不能据此证明会话仍有效。自动提醒基于实际采集产生的 `SOURCE_AUTH_REQUIRED`，非永久在线会话保证。

## 证据与范围

只导出页面选中平台、周期、日期、榜单标题和榜单容器的DOM快照；排除脚本、表单、iframe和应用状态。材料传输标记为 `persistent-browser-ranking-dom`，SHA-256针对这个明确的DOM快照计算，不声称它是原始HTTP响应。源名、日期、真实名次、覆盖与证据定位由原核心适配器继续验证。

2026-11-02已完成真实Top100现场验收：批次 `7682f820-92d1-4051-a395-c5fff8c3cd3a` 的微信三榜、抖音四榜均连续1–100名，连同Apple共800行已入库并通过材料/数据库逐行核验。单元测试中的100行均为合成数据，不作为真实榜单交付。两个离线Chrome测试验证日期动态属性、异步行加载和敏感输入排除。
