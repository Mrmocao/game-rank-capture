"""Generate additive V5 and the standalone, annotated installation contract; never edit applied V1/V4."""
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / 'backend/src/main/resources/db/migration'
schema = (base / 'V1__initial_schema.sql').read_text(encoding='utf-8')
v4 = (base / 'V4__chinese_comments.sql').read_text(encoding='utf-8')
existing = {(t, c) for t, c in re.findall(r'ALTER TABLE (\w+) MODIFY COLUMN (\w+)', v4)}
labels = dict(line.split('=', 1) for line in '''id=本表主键ID
name=名称
created_at=记录创建时间（UTC）
updated_at=记录更新时间（UTC）
version=乐观锁版本号；修改时校验防止并发覆盖
created_by=创建人或任务标识
updated_by=最后修改人标识
product_id=所属统一产品ID，关联product
platform_version_id=所属平台版本ID，关联platform_version
source_id=数据来源ID，关联data_source
first_observed_at=首次有效观察时间（UTC），不等于实际上线时间
released_on=来源提供的实际发布日期；未知为空
source_fields=来源原始字段JSON，不覆盖人工修正
config=配置JSON；不得包含明文密钥
authorization_notes=来源授权方式、使用范围及保存限制说明
external_chart_id=来源提供的稳定榜单标识
category_key=榜单分类口径，ALL为全分类，GAME为游戏
device_scope=榜单设备口径；DEFAULT表示来源未细分
enabled=是否启用该配置
methodology=来源统计方法与指标口径说明
schedule_spec=调度表达式；manual表示仅手动执行
timezone=任务业务时区
job_id=采集任务ID，关联collection_job
parent_run_id=重试所关联的上一次运行ID；首次为空
business_date=业务统计日（Asia/Shanghai），缺失不得用其他日期回填
run_mode=运行模式；DEMO为模拟，LIVE_PUBLIC为公开来源实采
stage=当前处理阶段，如FETCHED或PUBLISHED
idempotency_key=运行幂等键，同一次材料重试不得重复写入
started_at=运行开始时间（UTC）
finished_at=运行完成或失败时间（UTC）
heartbeat_at=最近任务心跳时间（UTC）
error_code=可检索的失败错误码；成功为空
error_message=脱敏错误说明，不记录凭据
metrics=运行计数、耗时及覆盖指标JSON
model_metadata=模型、提示词版本及调用状态JSON；未调用须明确标记
chart_id=榜单定义ID，关联chart_definition
run_id=生成快照的采集运行ID
source_url=原始来源URL，不含凭据
raw_locator=原始材料存储定位，结合哈希校验
raw_sha256=原始材料UTF-8内容的SHA-256摘要
captured_at=材料实际获取时间（UTC）
published_at=发布时间（UTC）；未发布为空
snapshot_id=具体榜单快照ID；历史引用不随新修订变化
positive_review_count=来源明确给出的好评数；不得从评分总量推算
source_metrics=来源指标JSON，保留单位、期间及是否估算
source_record_id=来源记录标识，用于定位原始条目
valid_until=证据有效期截止时间（UTC）；为空表示来源未声明
scope=证据适用的版本、地区和业务日JSON
metadata=证据来源属性及真实性标记JSON
confidence=识别置信度0到1；不能单独作为纯IAA依据
effective_at=结论或事件生效时间（UTC）
expires_at=记录有效期截止时间（UTC）
supersedes_id=被此记录取代的旧记录ID；保留旧记录
assessment_id=变现判定ID，关联monetization_assessment
evidence_id=证据ID，关联evidence
relation_kind=证据关系：SUPPORTS支持、CONFLICTS冲突
timeout_seconds=AI请求超时秒数，允许5至300
temperature=模型采样温度，允许0至2
content_sha256=已发布内容SHA-256摘要
rule_version_id=本次计算或报告冻结使用的规则版本ID
signal_type=市场信号类型，如NEW_OBSERVED、RAPID_RISE、SUSTAINED
reason=判定或操作的可读理由
rule_hit_id=确定性规则命中ID，关联rule_hit
pool_state=资格状态：PENDING_IDENTIFICATION待识别、OBSERVING观察、FORMAL正式、EXCLUDED排除
recorded_at=状态历史实际登记时间（UTC）
field_name=被人工覆盖的字段名称
value_json=人工覆盖值JSON，独立于原始采集字段
reference_version_id=选用参考的平台版本ID；可为空，非空须属于该产品
note=选用备注；不影响市场资格
generated_at=报告生成完成时间（UTC）
report_id=所属周报ID，关联weekly_report
main_reference_version_id=报告冻结的主要参考平台版本ID
display_order=报告内展示顺序，从1开始
from_product_id=身份关联前所属产品ID
to_product_id=身份关联后所属产品ID
reverts_event_id=本事件撤销的原始身份关联事件ID
evidence_refs=支撑操作的证据引用JSON
actor_id=操作者身份标识
action=审计操作名称
entity_type=被操作实体类型
entity_id=被操作实体ID，字符串形式
before_json=操作前脱敏数据快照
after_json=操作后脱敏数据快照
request_id=请求追踪ID，便于关联日志
operation=幂等操作名称
request_key=客户端幂等请求键
request_sha256=请求负载SHA-256；同键不同负载应报冲突
response_json=首次处理的响应JSON，用于重试复用'''.splitlines())
statuses = {'collection_run':'任务状态：RUNNING运行、SUCCEEDED成功、PARTIAL部分成功、FAILED失败',
 'ranking_snapshot':'快照状态：STAGED暂存、PUBLISHED已发布；失败不发布',
 'weekly_report':'报告状态：PUBLISHED已冻结发布；旧版保留',
 'idempotency_record':'幂等请求处理状态'}
statements = []
for table, body in re.findall(r'CREATE TABLE (\w+) \((.*?)\n\);', schema, re.S):
    parts, start, depth, quoted = [], 0, 0, False
    for i, ch in enumerate(body):
        if ch == "'": quoted = not quoted
        if not quoted:
            depth += (ch == '(') - (ch == ')')
            if ch == ',' and depth == 0:
                parts.append(body[start:i].strip()); start = i+1
    parts.append(body[start:].strip())
    for definition in parts:
        column = definition.split()[0]
        if column.upper().split(chr(40))[0] in {'KEY','UNIQUE','FOREIGN','CHECK','PRIMARY','CONSTRAINT'} or (table,column) in existing: continue
        description = statuses[table] if column == 'status' else labels[column]
        definition = re.sub(r"\s+COMMENT\s+'[^']*'", '', definition)
        definition = definition.replace(' PRIMARY KEY', ' NOT NULL').replace(' UNIQUE', '')
        statements.append(f"ALTER TABLE `{table}` MODIFY COLUMN {definition} COMMENT '{description}';")
out = '-- V5：补齐V4未覆盖的全部业务字段。保留原类型、默认值、索引和外键。\nSET NAMES utf8mb4;\n' + '\n'.join(statements)+'\n'
(base / 'V5__complete_column_comments.sql').write_text(out, encoding='utf-8')
(root/'contracts/schema.sql').write_text('-- 全新空库安装脚本（已有库使用Flyway增量迁移，不要重跑CREATE）。\n'+schema+'\n'+v4+'\n'+out, encoding='utf-8')
print(f'V5 adds {len(statements)} column comments; standalone contract updated.')
