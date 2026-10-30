-- Repeatable, clearly labelled development data. All records are synthetic.
INSERT INTO data_source (id,name,adapter_key,access_method,enabled,secret_ref,config,authorization_notes,version,created_at,updated_at)
VALUES (1,'DEMO synthetic source','demo-static','SIMULATED',TRUE,'demo-only',JSON_OBJECT('demo',true),'Synthetic fixture; no external authorization.',0,'2026-10-27 00:00:00.000','2026-10-27 00:00:00.000')
ON DUPLICATE KEY UPDATE updated_at=VALUES(updated_at);

INSERT INTO chart_definition (id,source_id,platform,region,external_chart_id,name,kind,category_key,device_scope,requested_top_n,enabled,methodology,config)
VALUES
 (3001,1,'WECHAT','CN','demo-wechat-heat','DEMO 微信人气榜','HEAT','ALL','DEFAULT',100,TRUE,'Synthetic top 100','{"demo":true}'),
 (3002,1,'DOUYIN','CN','demo-douyin-heat','DEMO 抖音热门榜','HEAT','ALL','DEFAULT',100,TRUE,'Synthetic top 100','{"demo":true}'),
 (3003,1,'IOS','CN','demo-ios-free','DEMO iOS 中国游戏免费榜','HEAT','GAME','IPHONE',100,TRUE,'Synthetic top 100','{"demo":true}'),
 (3004,1,'GOOGLE_PLAY','US','demo-gp-free-us','DEMO Google Play US 免费榜','HEAT','GAME','PHONE',100,TRUE,'Synthetic top 100','{"demo":true}')
ON DUPLICATE KEY UPDATE name=VALUES(name),enabled=VALUES(enabled);

INSERT INTO collection_job (id,source_id,name,schedule_spec,timezone,enabled,config,version)
VALUES (4001,1,'DEMO daily synthetic collection','manual','Asia/Shanghai',FALSE,'{"mode":"DEMO"}',0)
ON DUPLICATE KEY UPDATE name=VALUES(name);

INSERT INTO product (id,name,pool_state,first_observed_at,canonical_product_id,created_at,updated_at,version)
VALUES
 (1001,'DEMO 收纳小镇','FORMAL','2026-10-18 03:00:00.000',NULL,'2026-10-18 03:00:00.000','2026-10-25 03:00:00.000',0),
 (1002,'DEMO 星环消除','OBSERVING','2026-10-24 03:00:00.000',NULL,'2026-10-24 03:00:00.000','2026-10-25 03:00:00.000',0),
 (1003,'DEMO 星环消除','PENDING_IDENTIFICATION','2026-10-25 03:00:00.000',NULL,'2026-10-25 03:00:00.000','2026-10-25 03:00:00.000',0),
 (1004,'DEMO 混变冲突','OBSERVING','2026-10-25 03:00:00.000',NULL,'2026-10-25 03:00:00.000','2026-10-25 03:00:00.000',0)
ON DUPLICATE KEY UPDATE name=VALUES(name),pool_state=VALUES(pool_state),updated_at=VALUES(updated_at);

INSERT INTO platform_version (id,product_id,platform,external_id,name,developer,package_name,entry_url,released_on,first_observed_at,source_fields,created_at,updated_at)
VALUES
 (2001,1001,'WECHAT','demo-wx-1001','DEMO 收纳小镇','DEMO Studio',NULL,'https://example.invalid/demo/wechat/1001',NULL,'2026-10-18 03:00:00.000','{"demo":true,"tags":["收纳","益智"]}','2026-10-18 03:00:00.000','2026-10-25 03:00:00.000'),
 (2002,1001,'DOUYIN','demo-dy-1001','DEMO 收纳小镇','DEMO Studio',NULL,'https://example.invalid/demo/douyin/1001',NULL,'2026-10-18 03:00:00.000','{"demo":true,"tags":["收纳","益智"]}','2026-10-18 03:00:00.000','2026-10-25 03:00:00.000'),
 (2003,1001,'IOS','demo-ios-1001','DEMO 收纳小镇','DEMO Studio','com.example.demo.organize','https://example.invalid/demo/ios/1001',NULL,'2026-10-18 03:00:00.000','{"demo":true,"tags":["收纳","益智"]}','2026-10-18 03:00:00.000','2026-10-25 03:00:00.000'),
 (2004,1002,'WECHAT','demo-wx-1002','DEMO 星环消除','DEMO A Studio',NULL,'https://example.invalid/demo/wechat/1002',NULL,'2026-10-24 03:00:00.000','{"demo":true,"tags":["消除"]}','2026-10-24 03:00:00.000','2026-10-25 03:00:00.000'),
 (2005,1003,'WECHAT','demo-wx-1003','DEMO 星环消除','DEMO B Studio',NULL,'https://example.invalid/demo/wechat/1003',NULL,'2026-10-25 03:00:00.000','{"demo":true,"tags":["消除"]}','2026-10-25 03:00:00.000','2026-10-25 03:00:00.000'),
 (2006,1004,'IOS','demo-ios-1004','DEMO 混变冲突','DEMO Studio','com.example.demo.mixed','https://example.invalid/demo/ios/1004',NULL,'2026-10-25 03:00:00.000','{"demo":true,"tags":["益智"]}','2026-10-25 03:00:00.000','2026-10-25 03:00:00.000')
ON DUPLICATE KEY UPDATE name=VALUES(name),updated_at=VALUES(updated_at);

INSERT INTO collection_run (id,job_id,business_date,run_mode,status,stage,idempotency_key,started_at,finished_at,heartbeat_at,metrics,model_metadata)
VALUES
 (5001,4001,'2026-10-25','DEMO','SUCCEEDED','PUBLISHED','demo-run-2026-10-25','2026-10-25 03:00:00.000','2026-10-25 03:01:00.000','2026-10-25 03:01:00.000','{"charts":3,"entries":8}','{"provider":"none","mode":"DEMO"}'),
 (5002,4001,'2026-10-24','DEMO','PARTIAL','PUBLISHED','demo-run-2026-10-24','2026-10-24 03:00:00.000','2026-10-24 03:01:00.000','2026-10-24 03:01:00.000','{"charts":2,"entries":3}','{"provider":"none","mode":"DEMO"}')
ON DUPLICATE KEY UPDATE status=VALUES(status);

INSERT INTO ranking_snapshot (id,chart_id,run_id,business_date,source_date,revision,status,completeness,requested_top_n,covered_top_n,actual_count,source_url,raw_locator,raw_sha256,captured_at,published_at)
VALUES
 (6001,3001,5001,'2026-10-25','2026-10-25',1,'PUBLISHED','COMPLETE',100,100,3,'https://example.invalid/demo/wechat/2026-10-25','demo://wechat/2026-10-25','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','2026-10-25 03:00:10.000','2026-10-25 03:01:00.000'),
 (6002,3002,5001,'2026-10-25','2026-10-25',1,'PUBLISHED','COMPLETE',100,100,2,'https://example.invalid/demo/douyin/2026-10-25','demo://douyin/2026-10-25','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','2026-10-25 03:00:10.000','2026-10-25 03:01:00.000'),
 (6003,3003,5001,'2026-10-25','2026-10-25',1,'PUBLISHED','COMPLETE',100,100,2,'https://example.invalid/demo/ios/2026-10-25','demo://ios/2026-10-25','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','2026-10-25 03:00:10.000','2026-10-25 03:01:00.000'),
 (6004,3001,5002,'2026-10-24','2026-10-24',1,'PUBLISHED','PARTIAL',100,40,2,'https://example.invalid/demo/wechat/2026-10-24','demo://wechat/2026-10-24','dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd','2026-10-24 03:00:10.000','2026-10-24 03:01:00.000'),
 (6005,3002,5002,'2026-10-24','2026-10-24',1,'PUBLISHED','COMPLETE',100,100,1,'https://example.invalid/demo/douyin/2026-10-24','demo://douyin/2026-10-24','eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee','2026-10-24 03:00:10.000','2026-10-24 03:01:00.000')
ON DUPLICATE KEY UPDATE completeness=VALUES(completeness),covered_top_n=VALUES(covered_top_n);

INSERT INTO ranking_entry (id,snapshot_id,platform_version_id,rank_position,rating,rating_count,positive_review_count,source_metrics)
VALUES
 (7001,6001,2001,12,4.80,1200,NULL,'{"demo":true}'),
 (7002,6001,2004,5,NULL,NULL,NULL,'{"demo":true}'),
 (7003,6001,2005,8,4.10,NULL,NULL,'{"demo":true}'),
 (7004,6002,2002,35,NULL,900,NULL,'{"demo":true}'),
 (7005,6002,2004,18,NULL,NULL,NULL,'{"demo":true}'),
 (7006,6003,2003,68,4.70,3000,2500,'{"demo":true}'),
 (7007,6003,2006,22,4.00,NULL,NULL,'{"demo":true}'),
 (7008,6004,2001,30,NULL,NULL,NULL,'{"demo":true}'),
 (7009,6004,2004,11,NULL,NULL,NULL,'{"demo":true}'),
 (7010,6005,2002,40,NULL,NULL,NULL,'{"demo":true}')
ON DUPLICATE KEY UPDATE rank_position=VALUES(rank_position);

INSERT INTO evidence (id,platform_version_id,source_id,kind,source_url,source_record_id,locator,raw_sha256,excerpt,observed_at,scope,metadata,created_by,created_at)
VALUES
 (8001,2001,1,'IAA_DECLARATION','https://example.invalid/demo/evidence/2001','demo-evidence-2001','entries[0].monetization','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','DEMO fixture states ads-only for this version.', '2026-10-25 03:02:00.000','{"version":"demo-wx-1001"}','{"demo":true}','seed','2026-10-25 03:02:00.000'),
 (8002,2003,1,'IAA_CONFLICT','https://example.invalid/demo/evidence/2003','demo-evidence-2003','entries[0].iap','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','DEMO fixture contains an IAP conflict; not a pure IAA conclusion.', '2026-10-25 03:02:00.000','{"version":"demo-ios-1001"}','{"demo":true}','seed','2026-10-25 03:02:00.000')
ON DUPLICATE KEY UPDATE excerpt=VALUES(excerpt);

INSERT INTO monetization_assessment (id,platform_version_id,mode,method,confidence,reason,effective_at,created_by,created_at)
VALUES
 (9001,2001,'PURE_IAA','DEMO_EVIDENCE',0.95,'Synthetic evidence explicitly marks ads-only.', '2026-10-25 03:02:00.000','seed','2026-10-25 03:02:00.000'),
 (9002,2002,'PURE_IAA','DEMO_EVIDENCE',0.90,'Synthetic evidence explicitly marks ads-only.', '2026-10-25 03:02:00.000','seed','2026-10-25 03:02:00.000'),
 (9003,2003,'MIXED','DEMO_CONFLICT',NULL,'Synthetic conflict evidence keeps this version mixed.', '2026-10-25 03:02:00.000','seed','2026-10-25 03:02:00.000')
ON DUPLICATE KEY UPDATE reason=VALUES(reason);
INSERT INTO assessment_evidence (assessment_id,evidence_id,relation_kind) VALUES (9001,8001,'SUPPORTS'),(9002,8001,'SUPPORTS'),(9003,8002,'CONFLICTS') ON DUPLICATE KEY UPDATE relation_kind=VALUES(relation_kind);

INSERT INTO selection_record (id,product_id,reference_version_id,owner_name,status,selected_on,started_on,finished_on,note,created_by,updated_by,created_at,updated_at,version)
VALUES
 (10001,1001,2001,'叶工','SELECTED','2026-10-25',NULL,NULL,'DEMO 多人选用记录一','dev-user','dev-user','2026-10-25 04:00:00.000','2026-10-25 04:00:00.000',0),
 (10002,1001,2003,'杨灵芝','IN_PROGRESS','2026-10-24','2026-10-25',NULL,'DEMO 多人选用记录二','dev-user','dev-user','2026-10-24 04:00:00.000','2026-10-25 04:00:00.000',1)
ON DUPLICATE KEY UPDATE note=VALUES(note),updated_at=VALUES(updated_at);

INSERT INTO rule_version (id,version_number,status,rules_json,content_sha256,published_at,created_by,created_at)
VALUES (11001,1,'PUBLISHED','{"sustainedDays":10,"windowDays":14,"topN":50,"rapidRiseDays":7,"rapidRiseDelta":20,"reportDomesticLimit":5,"reportOverseasLimit":5}','demo-rule-sha256','2026-10-25 03:00:00.000','seed','2026-10-25 03:00:00.000')
ON DUPLICATE KEY UPDATE rules_json=VALUES(rules_json);
