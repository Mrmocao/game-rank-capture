# 商店榜单与海外采集运行手册

当前支持：Apple中国3榜（免费、付费、畅销）；AppBrain Google Play美英德法各5榜（免费、付费、畅销、新免费、新付费）。AppBrain是第三方，榜内缺失名次/部分覆盖真实保留；Recent为其估算近30日安装原文，不能当日活、收入或确定增长。Apple源日期按源时区保留并另记北京时间业务日；AppBrain日期时区未知，不擅自换算。

来源：`https://itunes.apple.com/cn/rss/{topfreeapplications|toppaidapplications|topgrossingapplications}/limit=100/genre=6014/json`；`https://www.appbrain.com/stats/google-play-rankings/{top_free|top_paid|top_grossing|top_new_free|top_new_paid}/game/{us|gb|de|fr}`。仅读取公开页，无付费、验证码绕过或Cookie复制。来源可能限流/改版；出现错误保持失败，可关闭目标。未取得长期再分发协议，不将原始材料公开发布。

## 运行和配置

1. `mvn package`、`npm --prefix frontend run build`；JDBC备份，部署后执行至Flyway V17。旧版本迁移文件不改。
2. 国内systemd继续每天08:30/23:00，含微信3、抖音4、Apple3。海外由`.github/workflows/overseas-capture.yml`在08:40/23:10运行；GitHub排队可能延迟，长期无人提交公开库时计划可能被平台停用，需在Actions查看状态并重新启用。
3. 在信息抓取配置中启用/停用AppBrain地区；`GET /api/v1/market/remote-capture-plan`只返回地区/榜型/TopN。远程目标不会进入服务器本机worker，手动入口指向真实Actions任务。
4. GitHub Repository Secrets仅存`OVERSEAS_CAPTURE_SSH_KEY`（专用ed25519私钥）及`OVERSEAS_CAPTURE_KNOWN_HOSTS`（经管理员确认的服务器公钥）。不存root、DB、AI或源站登录凭据。公有库使用标准免费runner，不购买额外服务。
5. 服务器root安装`scripts/receive-overseas.py`至`/opt/iaa-radar/shared/receive-overseas.py`，root:root644。独立账号iaa-overseas的authorized_keys使用`restrict,command="/usr/bin/sudo -n /usr/bin/python3 -I /opt/iaa-radar/shared/receive-overseas.py"`前缀。sudoers只允许该精确命令（无通配符、无参数）。用`visudo -cf`验证；禁止给此账号常规shell/转发权限。
6. 手工运行Actions可选择collect_only验证来源；默认实际交付。接收端限制24MB压缩、32MB展开、41成员、20范围、48小时内采集时间；检查原始哈希、精确来源URL和范围。Java重解析后入库，已有批次/原始材料幂等，周报生成新修订。没有成功材料时SOURCE_FAILED，绝不补空榜。

## 维护与排错

信息抓取页显示最近交付时间、条数、失败数及Actions链接；回执不是实时心跳。若回执陈旧或为空，检查Actions是否启用、任务日志、源站HTTP状态、服务器SSH22/网页18080可达、密钥与固定主机指纹。不要关闭StrictHostKeyChecking或把root私钥交给runner。更换密钥时生成新专用密钥，将新公钥加入受限列表、更新GitHub Secret并测试，随后撤旧公钥。撤销时先停Workflow，再删专用authorized_keys和sudoers。

原始数据仅在runner临时目录和服务器证据目录，runner结束销毁临时文件，不使用公开Actions artifact。服务器`shared/deliveries/overseas.json`保存最终回执；批次JSON、`capture-intake/<batch>/replay.log`和external_*记录可追溯；旧周报不覆盖。服务器继续保留既有内存边界；海外无常驻浏览器。

## 检查

离线：`mvn test`；`python -m unittest discover -s scripts/tests -v`；`npm --prefix frontend run build`。线上：Apple3榜与云端20范围实际执行；逐行对照原始材料/哈希/JDBC，核对日期、缺失、榜型，重复交付检查幂等及旧冻结报告未变化。真实结果记录在`docs/acceptance/2026-11-03-store-charts.md`，在取得结果前不宣称各国均成功。
