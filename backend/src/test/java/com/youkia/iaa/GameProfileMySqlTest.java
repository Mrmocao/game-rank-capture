package com.youkia.iaa;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.youkia.iaa.api.ApiException;
import com.youkia.iaa.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.youkia.iaa.api.SelectionRequest;
import com.youkia.iaa.api.SelectionPatch;

/** Real MySQL/JDBC, synthetic records rolled back. No AI, source calls or fictional product imports. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_MYSQL_TESTS",matches="true")
class GameProfileMySqlTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    record Scope(JdbcTemplate j,GameProfileService profiles,long product,long listing,long entity){}
    void transaction(java.util.function.Consumer<Scope> test)throws Exception{
        var env=new HashMap<String,String>();for(String l:Files.readAllLines(Path.of("../.env"))){int i=l.indexOf('=');if(i>0&&!l.startsWith("#"))env.put(l.substring(0,i),l.substring(i+1));}
        var ds=new DriverManagerDataSource(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"));var j=new JdbcTemplate(ds);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(tx->{try{
            var v=j.queryForMap("SELECT v.id,v.product_id FROM platform_version v WHERE v.platform='IOS' AND v.external_id NOT LIKE 'demo-%' LIMIT 1");
            long entity=((Number)v.get("id")).longValue(),product=((Number)v.get("product_id")).longValue();
            j.update("INSERT INTO market_listing(tenant_id,platform_version_id,market,language,first_observed_at,available_at) VALUES('SYNTHETIC-V23',?,'CN','zh-CN',?,?)",entity,today.minusDays(10).atStartOfDay(),today.minusDays(10).atStartOfDay());
            long listing=j.queryForObject("SELECT id FROM market_listing WHERE tenant_id='SYNTHETIC-V23' AND platform_version_id=?",Long.class,entity);
            test.accept(new Scope(j,new GameProfileService(j,mapper,"SYNTHETIC-V23"),product,listing,entity));
        }finally{tx.setRollbackOnly();}});
    }
    ObjectNode material(Scope c,String key,Object value,String app){var b=mapper.createObjectNode();b.put("listingId",c.listing);b.put("fieldKey",key);b.put("kind","manual");b.put("status","available");b.set("value",mapper.valueToTree(value));b.put("submitter","SYNTHETIC提交人");b.put("observedAt",today.minusDays(1).toString());b.put("sourceName","SYNTHETIC事务回滚材料");b.put("originFamily","SYNTHETIC");b.put("excerpt","仅为集成校验，不是真实市场证据");b.put("locator","SYNTHETIC记录位置");b.put("rights","internal");b.put("appVersion",app);b.put("mediaKind","");b.put("isSynthetic",true);return b;}
    long submit(Scope c,String key,Object value,String app){return ((Number)c.profiles.submit(material(c,key,value,app),UUID.randomUUID().toString()).get("id")).longValue();}
    JsonNode review(int rev){return mapper.valueToTree(Map.of("reviewer","SYNTHETIC复核人","reason","目标范围、原文、授权和矛盾检查的事务测试","decision","approved","expectedRevision",rev));}
    void approve(Scope c,String key,Object value,String app){c.profiles.review(submit(c,key,value,app),review(0));}
    Map<String,Object> profile(Scope c,LocalDate day){return c.profiles.profile(c.product,c.listing,null,null,day,"Asia/Shanghai");}
    Map<String,Object> field(Scope c,String key){return ((Map<String,Map<String,Object>>)profile(c,today).get("fields")).get(key);}
    @Test void submissionReviewConflictReplayAndExport()throws Exception{transaction(c->{
        approve(c,"version","1.0","");
        long id=submit(c,"summary","SYNTHETIC核心循环A","1.0");assertEquals("missing",field(c,"summary").get("status"));
        c.profiles.review(id,review(0));assertEquals("available",field(c,"summary").get("status"));
        assertThrows(ApiException.class,()->c.profiles.review(id,review(0)));
        assertNull(((Map<String,Map<String,Object>>)profile(c,today.minusDays(1)).get("fields")).get("summary").get("value"));assertEquals("not_yet_known",((Map<String,Map<String,Object>>)profile(c,today.minusDays(1)).get("fields")).get("summary").get("status"));assertTrue(c.profiles.history(c.listing,"summary",today.minusDays(1),"Asia/Shanghai").isEmpty());
        approve(c,"summary","SYNTHETIC核心循环B","1.0");assertEquals("conflict",field(c,"summary").get("status"));
        long observation=c.j.queryForObject("SELECT MIN(id) FROM profile_field_observation WHERE tenant_id='SYNTHETIC-V23' AND listing_id=? AND field_key='summary'",Long.class,c.listing);
        long fix=submit(c,"summary","SYNTHETIC核心循环B","1.0");var request=(ObjectNode)review(0);request.put("supersedesId",observation);c.profiles.review(fix,request);
        assertEquals("available",field(c,"summary").get("status"));assertEquals(3,c.profiles.history(c.listing,"summary",today,"Asia/Shanghai").size());
        var export=c.profiles.export(c.product,c.listing,today,"Asia/Shanghai");assertEquals("game-profile-2.3.0",export.get("schemaVersion"));assertFalse(((Map<?,?>)export.get("marketProfile")).containsKey("collaboration"));assertNotNull(export.get("currentCollaboration"));
        assertEquals(0,c.j.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'profile_%' AND column_comment=''",Integer.class));
    });}
    @Test void reportRevisionSeparatesObservationDateFromKnowledgeDate()throws Exception{transaction(c->{
        approve(c,"version","1.0","");approve(c,"summary","SYNTHETIC yesterday observation received today","1.0");
        LocalDate day=today.minusDays(1);LocalDateTime businessEnd=day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().minusNanos(1);
        var strict=new LinkedHashMap<String,Object>();c.profiles.enrichRecommendation(strict,c.product,c.entity,"CN",day,businessEnd);
        assertEquals("missing",((Map<?,?>)strict.get("profileFacts")).get("status"));
        var revised=new LinkedHashMap<String,Object>();c.profiles.enrichRecommendation(revised,c.product,c.entity,"CN",day,LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        var fields=(Map<String,Map<String,Object>>)((Map<?,?>)revised.get("profileFacts")).get("fields");
        assertEquals("available",fields.get("summary").get("status"));assertEquals("SYNTHETIC yesterday observation received today",GameProfileResolver.text(fields.get("summary").get("value")));
        var future=material(c,"developer","SYNTHETIC observed today","");future.put("observedAt",today.toString());c.profiles.review(((Number)c.profiles.submit(future,"SYNTHETIC-FUTURE-OBSERVATION").get("id")).longValue(),review(0));
        revised=new LinkedHashMap<>();c.profiles.enrichRecommendation(revised,c.product,c.entity,"CN",day,LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        fields=(Map<String,Map<String,Object>>)((Map<?,?>)revised.get("profileFacts")).get("fields");assertNull(fields.get("developer").get("value"));
        // The ordinary dossier's strict as-known-at view must remain unchanged.
        assertNull(((Map<String,Map<String,Object>>)profile(c,day).get("fields")).get("summary").get("value"));
    });}
    @Test void qualificationRequiresCurrentReviewedReleaseAndEvidence()throws Exception{transaction(c->{
        approve(c,"version","1.0","");var b=material(c,"mode","IAA","1.0");b.put("scopeConfirmed",true);b.put("adBasis","SYNTHETIC广告核查");b.put("noIapBasis","SYNTHETIC排除内购核查");c.profiles.review(((Number)c.profiles.submit(b,"SYNTHETIC-MODE").get("id")).longValue(),review(0));
        approve(c,"ad_formats",List.of("rewarded"),"1.0");approve(c,"no_iap_basis","SYNTHETIC完整核查","1.0");approve(c,"monetization_valid","SYNTHETIC CN 1.0","1.0");assertEquals("available",field(c,"mode").get("status"));assertEquals("simulation",((Map<?,?>)profile(c,today).get("qualification")).get("status"));assertEquals(false,((Map<?,?>)profile(c,today).get("qualification")).get("eligible"));
        approve(c,"version","2.0","");assertEquals("available",field(c,"version").get("status"));assertEquals("2.0",GameProfileResolver.text(field(c,"version").get("value")));assertEquals(false,((Map<?,?>)profile(c,today).get("qualification")).get("eligible"));assertEquals("stale",field(c,"mode").get("status"));
    });}
    @Test void tasksIdempotencyAndOptimisticRevisionDoNotChangeFacts()throws Exception{transaction(c->{
        var first=c.profiles.plan(c.listing,today,"Asia/Shanghai");assertTrue(((Number)first.get("created")).intValue()>0);assertEquals(0,c.profiles.plan(c.listing,today,"Asia/Shanghai").get("created"));
        var t=c.profiles.tasks(c.listing).getFirst();long id=((Number)t.get("id")).longValue();var b=mapper.valueToTree(Map.of("owner","SYNTHETIC负责人","status","in_progress","note","SYNTHETIC核查进度","expectedRevision",0));c.profiles.updateTask(id,b);assertThrows(ApiException.class,()->c.profiles.updateTask(id,b));assertEquals("missing",field(c,"mode").get("status"));
        var m=material(c,"name","SYNTHETIC名称","");long one=((Number)c.profiles.submit(m,"SYNTHETIC-IDEMPOTENT").get("id")).longValue();assertEquals(one,((Number)c.profiles.submit(m,"SYNTHETIC-IDEMPOTENT").get("id")).longValue());m.put("value","SYNTHETIC变更");assertThrows(ApiException.class,()->c.profiles.submit(m,"SYNTHETIC-IDEMPOTENT"));
    });}
    @Test void draftCannotRegisterReleaseAndRightsAndTenantAreEnforced()throws Exception{transaction(c->{
        var m=material(c,"version","9.9","9.9");m.put("kind","inference");m.set("modelMetadata",mapper.valueToTree(Map.of("model","SYNTHETIC","promptVersion","1","schemaVersion","1","inputHash","SYNTHETIC")));long id=((Number)c.profiles.submit(m,"SYNTHETIC-AI-VERSION").get("id")).longValue();assertThrows(IllegalArgumentException.class,()->c.profiles.review(id,review(0)));assertEquals(0,c.j.queryForObject("SELECT COUNT(*) FROM release_record WHERE listing_id=?",Integer.class,c.listing));
        var denied=material(c,"name","SYNTHETIC权限材料","");denied.put("rights","not_authorized");final long deniedId=((Number)c.profiles.submit(denied,"SYNTHETIC-DENIED").get("id")).longValue();assertThrows(ApiException.class,()->c.profiles.review(deniedId,review(0)));assertThrows(ApiException.class,()->new GameProfileService(c.j,mapper,"OTHER-TENANT").listing(c.listing));
    });}
    @Test void scopedOwnersAndCurrentCollaborationSurviveHistoricalMarketDay()throws Exception{transaction(c->{
        var service=new SelectionService(c.j,mapper,new ProductQueryService(c.j,"SYNTHETIC-V23"),"SYNTHETIC-V23");
        var first=service.create(c.product,new SelectionRequest("SYNTHETIC甲",c.entity,"SELECTED",today,null,null,"SYNTHETIC回滚",c.listing),"SYNTHETIC-OWNER-A");
        var second=service.create(c.product,new SelectionRequest("SYNTHETIC乙",c.entity,"SELECTED",today,null,null,"SYNTHETIC回滚",c.listing),"SYNTHETIC-OWNER-B");assertNotEquals(first.get("id"),second.get("id"));
        long id=((Number)first.get("id")).longValue();var patch=new SelectionPatch("SYNTHETIC甲",c.entity,"IN_PROGRESS",today,today,null,"SYNTHETIC当前进度",0,c.listing);service.update(id,patch);assertThrows(ApiException.class,()->service.update(id,patch));
        assertEquals(2,((List<?>)((Map<?,?>)profile(c,today.minusDays(2)).get("collaboration")).get("selections")).size());
        assertThrows(ApiException.class,()->new SelectionService(c.j,mapper,new ProductQueryService(c.j),"OTHER-TENANT").update(id,patch));
    });}
    @Test void aiSuccessFailureAndAbstentionKeepFactsUntouched()throws Exception{transaction(c->{
        approve(c,"name","SYNTHETIC原始名称","");long evidence=((Number)field(c,"name").get("evidenceId")).longValue();
        var provider=mock(AiConfigService.class);when(provider.runtime()).thenReturn(new AiConfigService.RuntimeConfig("http://test.invalid/v1","SYNTHETIC-model","SYNTHETIC-key",5,0,true));var client=mock(ProfileAiClient.class);
        var output=mapper.valueToTree(Map.of("claims",List.of(Map.of("fieldKey","name","value","SYNTHETIC候选名称","evidenceId",evidence,"quote","仅为集成校验","locator","摘录1")),"unknownFields",List.of(),"abstentionReason",""));doReturn(output).when(client).extract(any(),any());
        var service=new ProfileAiService(c.j,mapper,c.profiles,client,provider,new DataSourceTransactionManager(c.j.getDataSource()));
        ObjectNode b=mapper.valueToTree(Map.of("listingId",c.listing,"evidenceId",evidence,"actor","SYNTHETIC AI测试","modelTransferConfirmed",true,"fieldKeys",List.of("name")));
        var result=service.extract(b);assertEquals("SUCCEEDED",result.get("status"));assertEquals(true,service.extract(b).get("reused"));assertEquals("SYNTHETIC原始名称",GameProfileResolver.text(field(c,"name").get("value")));
        var beforeDeveloper=new LinkedHashMap<>(field(c,"developer"));b.putArray("fieldKeys").add("developer");doThrow(new ProfileAiClient.Failure("AI_HTTP_503")).when(client).extract(any(),any());assertEquals("FAILED",service.extract(b).get("status"));assertEquals(beforeDeveloper,field(c,"developer"));
        b.putArray("fieldKeys").add("publisher");doReturn(mapper.valueToTree(Map.of("claims",List.of(),"unknownFields",List.of("publisher"),"abstentionReason","没有可引用发行信息"))).when(client).extract(any(),any());assertEquals("ABSTAINED",service.extract(b).get("status"));assertFalse(c.profiles.tasks(c.listing).isEmpty());
        assertEquals("SYNTHETIC原始名称",GameProfileResolver.text(field(c,"name").get("value")));verify(client,times(3)).extract(any(),any());
    });}
    @Test void mediaOnlyStoresExternalReferenceAndExportIsFrozen()throws Exception{transaction(c->{
        var b=material(c,"description","SYNTHETIC实机材料说明","");b.put("mediaKind","gameplay_screenshot");b.put("sourceUrl","https://example.com/synthetic.png");c.profiles.review(((Number)c.profiles.submit(b,"SYNTHETIC-MEDIA").get("id")).longValue(),review(0));
        var media=(List<Map<String,Object>>)profile(c,today).get("media");assertEquals("external_only",media.getFirst().get("status"));assertEquals("gameplay_screenshot",media.getFirst().get("media_kind"));assertNull(media.getFirst().get("content_sha256"));var frozen=c.profiles.export(c.product,c.listing,today,"Asia/Shanghai");String hash=frozen.get("contentSha256").toString();approve(c,"publisher","SYNTHETIC新资料","");assertEquals(hash,frozen.get("contentSha256"));assertNotEquals(hash,c.profiles.export(c.product,c.listing,today,"Asia/Shanghai").get("contentSha256"));
    });}
    @Test void recommendationProjectionRequiresExactIdentityScopeAndKnownTime()throws Exception{transaction(c->{
        approve(c,"version","1.0","");approve(c,"summary","SYNTHETIC玩法","1.0");
        var input=new LinkedHashMap<String,Object>();input.put("iaaCurrent",true);input.put("gameplay",true);input.put("effortLower",2);c.profiles.enrichRecommendation(input,c.product,c.entity,"CN",today,LocalDateTime.now(ZoneOffset.UTC));
        var profile=(Map<?,?>)input.get("profileFacts");assertEquals(c.listing,profile.get("listingId"));assertEquals("CN",profile.get("market"));assertEquals(true,profile.get("isSynthetic"));assertEquals(false,input.get("iaaCurrent"));assertNull(input.get("gameplay"));assertFalse(input.containsKey("profileCoverage"));
        var other=new LinkedHashMap<String,Object>();c.profiles.enrichRecommendation(other,c.product,c.entity,"FR",today,LocalDateTime.now(ZoneOffset.UTC));assertNull(other.get("profileFacts"));assertEquals(false,other.get("iaaCurrent"));
        var past=new LinkedHashMap<String,Object>();c.profiles.enrichRecommendation(past,c.product,c.entity,"CN",today.minusDays(2),LocalDateTime.now(ZoneOffset.UTC));assertEquals("missing",((Map<?,?>)past.get("profileFacts")).get("status"));
    });}
}
