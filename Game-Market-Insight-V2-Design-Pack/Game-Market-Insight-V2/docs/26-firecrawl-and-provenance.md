# V2.5 Firecrawl选型与信息来源中心

日期：2026-10-30。本轮为设计优化；尚未接入Firecrawl或部署新版页面。

## 工具定位

采用可替换采集引擎：官方API直连优先，公开网页使用Firecrawl，搜索发现可使用Firecrawl Search，授权导入与人工浏览器研究作为补充。Java负责调度、身份、质量校验、MySQL存储及推荐规则；Firecrawl作为独立HTTP服务接入，不改写为Java。

先用云服务对公开样本做小规模PoC，再根据实际成本、部署要求决定自建。云版与自建版分别登记能力；官方文档说明自建核心包含scrape/crawl/map/search，LLM抽取需要配置模型，默认自建栈不包含云端Agent/Browser/Interact等完整能力。自建支持服务的存储不能直接假定全部替换为业务MySQL。

Search用于找URL；Scrape读取已知URL；Map发现站内链接；Crawl只对批准的网站路径限量运行。不要对整个应用商店无限递归。具体产品榜单深度、评论分页和小游戏权限仍逐站验收。

时效榜单设置maxAge=0并核实来源日期；普通档案允许明确缓存窗口。官方默认缓存可能为两天，HTTP成功不表示当日数据。榜单和评论抽取保留必要HTML与定位，防止正文清洗遗漏评论、榜项。保留id/gl/hl等有业务含义的查询参数。

## 导航与页面

实际使用新增“信息来源与证据”；配置管理新增“数据源与字段映射”，原“采集接入规划”升级为“采集工具与策略”。保持原有两级导航。

来源中心包含四个视图：字段记录、原始材料、搜索发现、采集运行。支持游戏、平台、市场、信息类别、日期、网站、发现方式、采集工具、采用状态筛选和导出。配置清单与已采记录分开，尚未接入的来源不得显示成功采集数。

字段记录列：数据日期、游戏/平台版本、信息项、来源值、原始网站、发现方式、采集工具、观察时间、证据状态、采用状态、操作。

点击记录展示：原始页面标题/URL、来源发布日期、实际抓取时间、缓存情况、原文片段与定位、解析值、AI处理记录、适用范围、冲突材料、采用理由和影响的推荐。支持从单款档案字段及周报推荐理由反向跳转。

原始URL缺失时显示“待补”，网站首页只标“网站入口”，不可伪装为该字段证据。搜索摘要只能作为待验证线索；读取原始页面并校验后才产生事实。

## 网站、发现、采集与分析分别记录

source_site：原始网站及主体，不把Firecrawl写成游戏数据的原始出处。

discovery_run：Firecrawl搜索/其他搜索API/浏览器搜索/人工提交/站内链接；记录关键词、提供方、国家语言、时间、结果顺序及目标URL。搜索结果顺序绝不能写成游戏榜单名次。

fetch_run：直连接口/Firecrawl云/Firecrawl自建/授权浏览器/文件导入；记录请求ID、部署模式、版本、实际URL、状态、观察时间、缓存年龄、覆盖、费用与失败原因。

document_version：材料hash、允许保存的原文、政策与保留期限。field_evidence关联具体字段、值、平台版本及材料定位。analysis_run记录AI/规则版本、输入证据与结论。adoption_decision记录采用/待核/冲突/过期/不采用及裁决依据。

一条记录可以“由浏览器搜索发现，再用Firecrawl采集，然后AI提取”；界面同时展示这些阶段，不能用单一“来源方式”互相覆盖。同一原文经多种工具获得仍是一个根来源。

## 开发与验收

扩展原source_definition与collection_task，新增discovery_run、discovery_result、fetch_engine、fetch_run、document_version、field_evidence及adoption_decision逻辑对象；复用既有证据表，避免重复建立两套事实库。凭证仅服务端保存，前端原型不请求Firecrawl。

新增API设计：GET /sources、GET /field-evidence、GET /discovery-runs、GET /fetch-runs、GET /evidence/{id}/lineage；列表分页且服务端按组织、字段范围和来源权限过滤。导出保留筛选条件、时间及证据ID，不默认导出受限原文。

验收：搜索发现但未读原文不能成为正式事实；浏览器搜索与API搜索标签准确；缓存旧榜不能当当日榜；同源多工具不重复计证据；跨市场记录不串用；AI无引用不采用；缺URL不造链接；部分采集保留覆盖；历史查询不引入未来知识；权限删除传播到派生结果；云/自建能力不冒充已验收；工具失败仍可输出缺失及补证报告。

## 核验依据

通过GitHub插件读取firecrawl/firecrawl的README、SELF_HOST.md与LICENSE，并核对官方文档。许可证标为AGPLv3；自建或修改时应登记适用许可要求。

- https://github.com/firecrawl/firecrawl
- https://docs.firecrawl.dev/features/search
- https://docs.firecrawl.dev/features/scrape
- https://docs.firecrawl.dev/contributing/open-source-or-cloud
- https://docs.firecrawl.dev/contributing/self-host

此次核验为文档与源码说明阅读，没有以目标网站实测Firecrawl成功率。在线原型仍为V2.3；本轮更新下载设计包，不宣称来源中心已上线。
