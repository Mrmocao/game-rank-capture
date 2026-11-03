package com.youkia.iaa;
import com.youkia.iaa.service.*;
import com.youkia.iaa.api.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic decisions against actual source rows, always rolled back. No real identity asserted. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_MYSQL_TESTS",matches="true")
class SourceIdentityMySqlTest {
 @Test void officialAssociationDoesNotLeakLaterIdentityChangesIntoKnowledgeReplay()throws Exception{
  var fixture=new MarketCaptureMySqlTest();var c=fixture.context();
  c.tx().executeWithoutResult(tx->{try{
   var market=fixture.service(c);LocalDate day=market.latest();var service=new SourceIdentityService(c.jdbc(),fixture.mapper,c.tenant());
   var candidate=c.jdbc().queryForMap("SELECT v.external_id externalId FROM platform_version v JOIN market_listing l ON l.platform_version_id=v.id WHERE v.platform='IOS' AND l.tenant_id=? LIMIT 1",c.tenant());
   var cutoff=LocalDateTime.now(ZoneOffset.UTC);var source=new LinkedHashMap<String,Object>();source.put("source_key","apple-games");source.put("platform","IOS");source.put("region","cn");source.put("external_id",candidate.get("externalId"));
   service.enrich(List.of(source),day,cutoff);assertNotNull(source.get("unified_product_id"));
   c.jdbc().update("UPDATE platform_version SET updated_at=? WHERE platform='IOS' AND external_id=?",cutoff.plusMinutes(1),candidate.get("externalId"));
   source.remove("unified_product_id");source.remove("unified_product_name");source.remove("association_basis");service.enrich(List.of(source),day,cutoff);assertNull(source.get("unified_product_id"));
  }finally{tx.setRollbackOnly();}});
 }
 @Test void crossPlatformReportRevisionsFreezeAndReuse()throws Exception{
  var fixture=new MarketCaptureMySqlTest();var c=fixture.context();var mapper=fixture.mapper;
  c.tx().executeWithoutResult(tx->{try{
   var identity=new SourceIdentityService(c.jdbc(),mapper,c.tenant());var market=fixture.service(c);LocalDate day=market.latest();
   var body=mapper.createObjectNode().put("action","LINK").put("actor","SYNTHETIC回滚测试").put("effectiveDate",day.toString()).put("evidenceUrl","https://example.com/SYNTHETIC").put("excerpt","仅用于跨平台冻结测试的合成证据，全部回滚").put("reason","测试身份关联不会修改旧报告").put("confirmedSameProduct",true).put("productName","SYNTHETIC合成跨平台关联");
   var members=body.putArray("members");
   for(String p:List.of("WECHAT","DOUYIN")){
    var row=((List<Map<String,Object>>)market.candidates(day,p,null).get("items")).stream().filter(r->((List<Map<String,Object>>)r.get("rankings")).stream().anyMatch(b->Set.of("popularity","most_played").contains(b.get("chartKey")))).findFirst().orElseThrow();
    members.addObject().put("observationId",((Number)row.get("observationId")).longValue()).put("rank",((Number)row.get("rank")).intValue()).put("revision",0);
   }
   var linked=identity.review(body,UUID.randomUUID().toString());String product="product:"+linked.get("productId");
   var reports=new WeeklyReportService(c.jdbc(),mapper,new ResearchService(c.jdbc(),mapper),c.tenant());
   var first=reports.generate(day.minusDays(6),day.plusDays(1));var repeat=reports.generate(day.minusDays(6),day.plusDays(1));assertEquals(first.get("id"),repeat.get("id"));assertEquals(true,repeat.get("reused"));
   String frozen=mapper.writeValueAsString(first.get("researchSnapshot"));var proof=mapper.readTree(frozen).path("captureResearchLeads").path("productEvidence").path(product);assertEquals(2,proof.path("currentPlatformCount").asInt());assertEquals(1,proof.path("originFamilyCount").asInt());assertTrue(proof.path("confirmedIdentity").asBoolean());
   body.put("action","UNLINK");for(var m:members)((com.fasterxml.jackson.databind.node.ObjectNode)m).put("revision",1);identity.review(body,UUID.randomUUID().toString());
   var changed=reports.generate(day.minusDays(6),day.plusDays(1));assertNotEquals(first.get("id"),changed.get("id"));assertEquals(frozen,mapper.writeValueAsString(reports.report(Long.parseLong(first.get("id").toString())).get("researchSnapshot")));
  }catch(Exception e){throw new RuntimeException(e);}finally{tx.setRollbackOnly();}});
 }
 @Test void linkClassifyRevokeProvenanceCutoffAndTenant()throws Exception{
  var fixture=new MarketCaptureMySqlTest();var c=fixture.context();var mapper=fixture.mapper;
  c.tx().executeWithoutResult(tx->{try{
   var service=new SourceIdentityService(c.jdbc(),mapper,c.tenant());var market=fixture.service(c);LocalDate day=market.latest();
   var candidate=(Map<String,Object>)((List<?>)market.candidates(day,"DOUYIN",null).get("items")).getFirst();
   var b=mapper.createObjectNode().put("action","LINK").put("actor","SYNTHETIC回滚核验").put("effectiveDate",day.toString()).put("evidenceUrl","https://example.com/SYNTHETIC").put("excerpt","合成测试原文至少十字，不是真实身份依据").put("reason","合成关联仅用于事务回滚验证").put("confirmedSameProduct",true).put("productName","SYNTHETIC跨平台产品");
   var member=b.putArray("members").addObject().put("observationId",((Number)candidate.get("observationId")).longValue()).put("rank",((Number)candidate.get("rank")).intValue()).put("revision",((Number)((Map<?,?>)candidate.getOrDefault("identityReview",Map.of("revision",0))).get("revision")).longValue());
   int oldCount=service.history(member.path("observationId").asLong(),member.path("rank").asInt()).size();long revision=member.path("revision").asLong();var before=LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);String key=UUID.randomUUID().toString();
   var first=service.review(b,key);assertNotNull(first.get("productId"));assertEquals(true,service.review(b,key).get("reused"));
   c.jdbc().update("UPDATE product SET name='SYNTHETIC随后改名' WHERE id=?",first.get("productId"));
   assertEquals("SYNTHETIC跨平台产品",service.states(day,LocalDateTime.now(ZoneOffset.UTC)).values().stream().filter(r->first.get("productId").equals(r.get("product_id"))).findFirst().orElseThrow().get("product_name"));
   assertFalse(service.states(day,before).values().stream().anyMatch(r->first.get("productId").equals(r.get("product_id"))));
   b.put("reason","不同内容不得复用同一个幂等请求键");assertThrows(ApiException.class,()->service.review(b,key));
   assertThrows(ApiException.class,()->service.review(b,UUID.randomUUID().toString()),"stale revision must conflict");
   member.put("revision",++revision);b.put("action","CLASSIFY").put("family","消除").put("excerpt","人工玩法证据显示通过三个相同元素消除过关");service.review(b,UUID.randomUUID().toString());
   member.put("revision",++revision);b.put("action","UNLINK").put("excerpt","发现发行方声明并非同一个产品所以撤销关联");service.review(b,UUID.randomUUID().toString());
   var history=service.history(member.path("observationId").asLong(),member.path("rank").asInt());var last=history.getFirst();assertNull(last.get("product_id"));assertEquals("消除",last.get("gameplay_family"));
   var evidence=(com.fasterxml.jackson.databind.JsonNode)last.get("evidence");assertTrue(evidence.path("gameplayEvidence").path("excerpt").asText().contains("三个相同元素"));assertTrue(evidence.path("identityEvidence").path("excerpt").asText().contains("撤销关联"));
   assertEquals(oldCount+3,history.size(),"three new immutable lifecycle events");
   var other=new SourceIdentityService(c.jdbc(),mapper,"SYNTHETIC-OTHER-TENANT");assertThrows(ApiException.class,()->other.history(member.path("observationId").asLong(),member.path("rank").asInt()));assertTrue(other.products("SYNTHETIC").isEmpty());
   member.put("revision",++revision);b.put("action","CLEAR_GAMEPLAY");service.review(b,UUID.randomUUID().toString());assertNull(service.history(member.path("observationId").asLong(),member.path("rank").asInt()).getFirst().get("gameplay_family"));
   assertEquals(0,c.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'source_entity_review_%' AND column_comment=''",Integer.class));
  }finally{tx.setRollbackOnly();}});
 }
}
