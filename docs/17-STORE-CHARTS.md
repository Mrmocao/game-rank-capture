# 商店榜单与海外采集运行手册

当前支持：Apple中国3榜（免费、付费、畅销）；AppBrain Google Play美英德法各5榜（免费、付费、畅销、新免费、新付费）。AppBrain是第三方，榜内缺失名次/部分覆盖真实保留；Recent为其估算近30日安装原文，不能当日活、收入或确定增长。Apple源日期按源时区保留并另记北京时间业务日；AppBrain日期时区未知，不擅自换算。

来源：`https://itunes.apple.com/cn/rss/{topfreeapplications|toppaidapplications|topgrossingapplications}/limit=100/genre=6014/json`；`https://www.appbrain.com/stats/google-play-rankings/{top_free|top_paid|top_grossing|top_new_free|top_new_paid}/game/{us|gb|de|fr}`。仅读取公开页，无付费、验证码绕过或Cookie复制。来源可能限流/改版；出现错误保持失败，可关闭目标。未取得长期再分发协议，不将原始材料公开发布。

## 运行和配置

1. `mvn package`、`npm --prefix frontend run build`；JDBC备份，部署后执行至Flyway V17。旧版本迁移文件不改。
2. 国内systemd继续每天08:30/23:00，含微信3、抖音4、Apple3。海外由`.github/workflows/overseas-capture.yml`在08:40/23:10运行；GitHub排队可能延迟，长期无人提交公开库时计划可能被平台停用，需在Actions查看状态并重新启用。
3. 在信息抓取配置中启用/停用AppBrain地区；`GET /api/v1/market/remote-capture-plan`只返回地区/榜型/TopN。远程目标不会进入服务器本机worker，手动入口指向真实Actions任务。
4. GitHub Repository Secrets仅存`OVERSEAS_CAPTURE_TOKEN`（随机48字节专用令牌）及`OVERSEAS_CAPTURE_CA_CERT`（专用证书PEM，校验服务器IP SAN）。不存root、DB、AI或源站登录凭据。公开库使用标准免费runner，不购买额外服务。
5. 服务器安装`scripts/overseas-http-receiver.py`、`scripts/receive-overseas.py`至`/opt/iaa-radar/shared/`，root:root644；令牌`/etc/iaa-radar/overseas-token`、TLS私钥`overseas-tls.key`仅root600。systemd `iaa-overseas-receiver`监听loopback18642、MemoryMax512M；nginx443只代理`/overseas/plan`和`/overseas/deliver`，其他404，24MB上限、每IP每分钟2请求/burst4。请求先鉴权再读取，未授权401；接收器仅调用固定Java回放链。TLS证书有效期2年，到期前重新签发并更新GitHub信任证书。完整可部署配置见scripts/systemd/iaa-overseas-receiver.service、scripts/nginx/iaa-overseas.conf；不开放数据库或后台管理接口。曾测试的SSH回传凭据已撤销，不依赖22/18080的海外可达性。
6. 手工运行Actions可选择collect_only验证来源；默认实际交付。接收端限制24MB压缩、32MB展开、41成员、20范围、48小时内采集时间；检查原始哈希、精确来源URL和范围。Java重解析后入库，已有批次/原始材料幂等，周报生成新修订。没有成功材料时SOURCE_FAILED，绝不补空榜。

部署已有Java接收链后，可在服务器checkout根目录以root执行 `bash scripts/install-overseas-receiver.sh`；重复运行保留已有令牌与证书，不输出凭据。通过受控运维会话将令牌/证书配置为上述GitHub Secrets。服务器更换IP时须同时更新collector固定地址、nginx server_name和证书SAN。

## 维护与排错

信息抓取页显示最近交付时间、条数、失败数及Actions链接；回执不是实时心跳。若回执陈旧或为空，检查Actions是否启用、任务日志、源站HTTP状态、服务器443可达、专用令牌和TLS证书是否到期。不要关闭证书验证或把root凭据交给runner。令牌轮换时更新服务器文件和GitHub Secret后重启接收器，验证成功。撤销时先停Workflow，再停接收器并移除专用nginx配置。

原始数据仅在runner临时目录和服务器证据目录，runner结束销毁临时文件，不使用公开Actions artifact。服务器`shared/deliveries/overseas.json`保存最终回执；批次JSON、`capture-intake/<batch>/replay.log`和external_*记录可追溯；旧周报不覆盖。服务器继续保留既有内存边界；海外无常驻浏览器。

## 检查

离线：`mvn test`；`python -m unittest discover -s scripts/tests -v`；`npm --prefix frontend run build`。线上：Apple3榜与云端20范围实际执行；逐行对照原始材料/哈希/JDBC，核对日期、缺失、榜型，重复交付检查幂等及旧冻结报告未变化。真实结果记录在`docs/acceptance/2026-11-03-store-charts.md`，在取得结果前不宣称各国均成功。
