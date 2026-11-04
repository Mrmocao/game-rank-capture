package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.*;
import com.youkia.capture.web.*;
import com.youkia.iaa.service.SecretCipher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnlineWebCaptureTest {
    @TempDir Path root;
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"),ZoneOffset.UTC);
    JdbcTemplate jdbc;CaptureWorkspaceStore store;OnlineWebCaptureService web;WebTransport transport;String key;
    WebCaptureRule rule(String selector){return new WebCaptureRule("https://example.com/",selector,List.of(new WebCaptureRule.Field("title","h2","text",true)),1,100,0,false);}
    @BeforeEach void setup()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");jdbc=new JdbcTemplate(ds);
        for(String file:List.of("V14__capture_workspace.sql","V17__overseas_store_charts.sql","V18__online_web_capture.sql"))try(var in=getClass().getResourceAsStream("/db/migration/"+file);var c=ds.getConnection()){String sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8).replace("UTC_TIMESTAMP(3)","CURRENT_TIMESTAMP(3)");ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}
        store=new CaptureWorkspaceStore(jdbc,mapper,clock);store.initialize("a");transport=mock(WebTransport.class);when(transport.providerUrl(anyString())).thenAnswer(inv->inv.getArgument(0));
        when(transport.fetch(anyString(),any(),any())).thenAnswer(inv->new RawMaterial(java.net.URI.create(((WebCaptureRule)inv.getArgument(1)).url()),clock.instant(),"fixture","<article><h2>Fixture</h2></article>".getBytes(StandardCharsets.UTF_8)));
        web=new OnlineWebCaptureService(store,transport,new SecretCipher("test-encryption"),root,"");
        key=(String)store.add("a",new CaptureWorkspaceStore.TargetEdit("Fixture","https://example.com/","title","","web-http",false,0)).get("target_key");
    }
    @Test void draftPreviewPublishAndVersionRollbackAreIndependent() {
        web.saveRule("a",key,new OnlineWebCaptureService.RuleEdit(0,rule("article")));
        assertThrows(CaptureException.class,()->web.execute("a",key,null));
        var preview=web.execute("a",key,1);assertEquals("SUCCEEDED",preview.get("status"));
        web.publish("a",key,1,1,(String)preview.get("id"));assertEquals(1,web.state("a",key).get("published_version"));
        web.saveRule("a",key,new OnlineWebCaptureService.RuleEdit(2,rule(".missing")));
        assertEquals("SUCCEEDED",web.execute("a",key,null).get("status"));var failed=web.execute("a",key,2);assertEquals("FAILED",failed.get("status"));
        assertThrows(CaptureException.class,()->web.publish("a",key,2,3,(String)failed.get("id")));assertEquals(1,web.state("a",key).get("published_version"));
        var retry=web.execute("a",key,1);web.publish("a",key,1,3,(String)retry.get("id"));assertEquals(2,web.state("a",key).get("draft_version"));
    }
    @Test void publicationRequiresExactRecentSuccessfulPreviewAndOptimisticVersion() {
        web.saveRule("a",key,new OnlineWebCaptureService.RuleEdit(0,rule("article")));var p=web.execute("a",key,1);
        assertThrows(CaptureException.class,()->web.publish("a",key,1,0,(String)p.get("id")));
        jdbc.update("UPDATE capture_web_run SET finished_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(90000)),p.get("id"));
        assertThrows(CaptureException.class,()->web.publish("a",key,1,1,(String)p.get("id")));assertThrows(CaptureException.class,()->web.saveRule("a",key,new OnlineWebCaptureService.RuleEdit(0,rule("article"))));
    }
    @Test void tenantCannotReadOrPublishAnotherWorkspacesEvidence() {
        web.saveRule("a",key,new OnlineWebCaptureService.RuleEdit(0,rule("article")));var p=web.execute("a",key,1);
        assertThrows(CaptureException.class,()->web.detail("b",(String)p.get("id")));assertThrows(IllegalArgumentException.class,()->web.rules("b",key));assertTrue(web.runs("b",null).isEmpty());
    }
    @Test void errorsKeepEvidenceAndDoNotCreateBusinessRecords()throws Exception {
        web.saveRule("a",key,new OnlineWebCaptureService.RuleEdit(0,rule(".missing")));var p=web.execute("a",key,1);assertEquals("FAILED",p.get("status"));assertTrue(Files.exists(root.resolve((String)p.get("raw_locator"))));assertNotNull(p.get("result_json"));assertNull(jdbc.queryForMap("SELECT * FROM capture_web_run WHERE id=?",p.get("id")).get("active_scope"));
    }
    @Test void providerSecretsAreEncryptedOmittedAndNeverReusedOnNewHost() {
        var p=web.saveProvider("a",new OnlineWebCaptureService.ProviderEdit("https://api.firecrawl.dev/v2","secret-test",false,true,0));assertEquals(true,p.get("apiKeyConfigured"));assertFalse(p.toString().contains("secret-test"));assertFalse(jdbc.queryForObject("SELECT api_key_ciphertext FROM capture_web_provider",String.class).contains("secret-test"));
        assertThrows(CaptureException.class,()->web.saveProvider("a",new OnlineWebCaptureService.ProviderEdit("https://different.example.com/v2","",false,true,1)));
        assertThrows(CaptureException.class,()->web.saveProvider("a",new OnlineWebCaptureService.ProviderEdit("https://api.firecrawl.dev/v2","",false,true,0)));
        assertFalse(web.provider("b").toString().contains("secret-test"));
    }
    @Test void enabledDynamicRulesParticipateInScheduledBatchWithoutFixedSourceRequests() {
        web.saveRule("a",key,new OnlineWebCaptureService.RuleEdit(0,rule("article")));var p=web.execute("a",key,1);web.publish("a",key,1,1,(String)p.get("id"));
        var t=store.target("a",key);store.update("a",key,new CaptureWorkspaceStore.TargetEdit("Fixture",(String)t.get("source_url"),"title","","web-http",true,((Number)t.get("revision")).intValue()));
        assertEquals(10,store.enabledRequests("a").size());assertEquals(1,web.runEnabled("a").size());assertTrue(web.runs("a",key).stream().anyMatch(r->"LIVE".equals(r.get("run_mode"))));
    }
}
