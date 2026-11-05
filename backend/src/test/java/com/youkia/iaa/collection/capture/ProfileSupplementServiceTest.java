package com.youkia.iaa.collection.capture;
import com.youkia.capture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileSupplementServiceTest {
 @TempDir Path root;
 GameMetricTest db;ProfileSupplementService service;boolean fail;int calls;
 @BeforeEach void setup() throws Exception {
  db=new GameMetricTest();db.root=root;db.setup();
  try(var in=getClass().getResourceAsStream("/db/migration/V22__profile_supplement_flow.sql");var c=db.jdbc.getDataSource().getConnection()) {
   ScriptUtils.executeSqlScript(c,new ByteArrayResource(new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("name_key(120)","name_key").getBytes(StandardCharsets.UTF_8)));
  }
  service=new ProfileSupplementService(new CaptureWorkspaceStore(db.jdbc,db.mapper,db.clock),root,name->{calls++;if(fail)throw new CaptureException("SOURCE_AUTH_REQUIRED","fixture");return List.of(material());});
 }
 RawMaterial material() throws Exception {
  var visible=Map.of("format","diandian-visible-v1","name","Fixture","header","App ID\n123456\n价格\n免费","storeLinks",List.of("https://apps.apple.com/cn/app/id123456"),"sections",Map.of("基本信息","开发者\nDeveloper\n版本\n1.0","全球速览","下载量\n>100万\n收入\n>$1亿"));
  return new RawMaterial(URI.create("https://app.diandian.com/app/fixture/ios?country=75"),db.clock.instant(),"fixture",db.mapper.writeValueAsBytes(visible));
 }
 @Test void duplicateNamesQueueOnceAndDoNotBecomeMiniGameMetrics() throws Exception {
  long mini=db.seed("WECHAT",null,"bestseller");db.seed("IOS","123456","free");
  String before=db.jdbc.queryForObject("SELECT bundle_json FROM external_ranking_observation WHERE id=?",String.class,mini);
  assertEquals(1,service.enqueue("a").get("queued"));assertEquals(0,service.enqueue("a").get("queued"));
  assertEquals("SUCCEEDED",service.tick("a").get("status"));assertEquals(1,calls);
  assertEquals("CACHED",service.accept("a",material()).get("status"));
  assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM capture_profile_document",Integer.class));
  assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM capture_metric_observation",Integer.class));
  assertEquals(before,db.jdbc.queryForObject("SELECT bundle_json FROM external_ranking_observation WHERE id=?",String.class,mini));
  var rows=(List<Map<String,Object>>)service.observation("a",mini).get("rows");assertEquals(1,((List<?>)rows.getFirst().get("profiles")).size());
  assertThrows(CaptureException.class,()->service.observation("b",mini));assertThrows(CaptureException.class,()->service.accept("b",material()));
 }
 @Test void authFailureStopsRepeatedRequestsAndPreservesSuccess() throws Exception {
  db.seed("WECHAT",null,"bestseller");service.accept("a",material());fail=true;
  assertEquals("SOURCE_AUTH_REQUIRED",service.tick("a").get("errorCode"));
  assertEquals(false,service.tick("a").get("executed"));assertEquals(1,calls);
  assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM capture_profile_document",Integer.class));
 }
 @Test void disabledTargetsCancelPendingAndWrongPlatformsStaySeparate() throws Exception {
  db.seed("WECHAT",null,"bestseller");service.accept("a",material());service.enqueue("a");
  long google=db.seed("GOOGLE_PLAY","123456","free");
  var rows=(List<Map<String,Object>>)service.observation("a",google).get("rows");assertTrue(((List<?>)rows.getFirst().get("profiles")).isEmpty());
  db.jdbc.update("UPDATE capture_target_config SET enabled=FALSE WHERE source_key='gravity-wechat'");
  assertEquals("CANCELLED",service.tick("a").get("status"));assertEquals(0,calls);
 }
}
