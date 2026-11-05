package com.youkia.capture.profiles;
import com.youkia.capture.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DiandianProfileParserTest {
 String fixture() throws Exception {return new String(getClass().getResourceAsStream("/metrics/diandian-visible.json").readAllBytes(),StandardCharsets.UTF_8);}
 RawMaterial raw(String text){return new RawMaterial(URI.create("https://app.diandian.com/app/xoupuzpmowm36tl/ios?market=1&country=75"),Instant.parse("2026-11-05T07:00:00Z"),"fixture",text.getBytes(StandardCharsets.UTF_8));}
 @Test void visibleFieldsKeepGlobalPreviewUnquantified() throws Exception {
  var d=new DiandianProfileParser().parse(raw(fixture()));
  assertEquals("无尽冬日",d.name());assertEquals("6478492012",d.externalId());
  assertEquals("com.gof.china",d.fields().get("Bundle ID"));assertEquals("4.80",d.fields().get("评分"));
  assertTrue(d.fields().get("热门内购").contains("¥30.00"));
  assertTrue(d.fields().get("全球速览原文").contains(">$1亿"));
  assertFalse(d.fields().containsKey("收入"));assertFalse(d.fields().containsKey("下载量"));
  assertTrue(d.limitations().stream().anyMatch(s->s.contains("未核验")));
 }
 @Test void wrongIdLoginAndWrongHostFailClosed() throws Exception {
  assertThrows(CaptureException.class,()->new DiandianProfileParser().parse(raw("{\"format\":\"diandian-visible-v1\",\"header\":\"登录/注册\"}")));
  assertThrows(CaptureException.class,()->new DiandianProfileParser().parse(raw(fixture().replace("id6478492012","id123456789"))));
  assertFalse(DiandianProfileParser.detailUrl(URI.create("https://app.diandian.com.evil.test/app/abc/ios?country=75")));
  assertFalse(DiandianProfileParser.detailUrl(URI.create("https://app.diandian.com/app/abc/ios?country=26")));
 }
 @Test void nameCandidatesAreConservativeAndNotIdentityAssertions() {
  assertTrue(DiandianProfileParser.candidate("浪漫餐厅","浪漫餐厅™-Gossip Harbor"));
  assertTrue(DiandianProfileParser.candidate("三国：冰河时代","三国:冰河时代"));
  assertFalse(DiandianProfileParser.candidate("无尽冬日","无尽冬日攻略"));
  assertFalse(DiandianProfileParser.candidate("","其他游戏"));
 }
 @Test void skeletonLabelsCannotBecomeDeveloperValues() throws Exception {
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var node=mapper.readTree(fixture());
  ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("sections")).put("基本信息","基本信息\n开发者\n发布日期\n-\n更新日期\n-");
  var e=assertThrows(CaptureException.class,()->new DiandianProfileParser().parse(raw(mapper.writeValueAsString(node))));
  assertEquals("PROFILE_FIELDS_MISSING",e.code());
 }
}
