package com.youkia.capture;

import com.youkia.capture.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WebCaptureTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"),ZoneOffset.UTC);
    WebCaptureRule rule(){return new WebCaptureRule("https://example.com/","article",List.of(new WebCaptureRule.Field("title","h2","text",true),new WebCaptureRule.Field("link","a","href",false)),2,100,0,false);}
    RawMaterial raw(String text){return new RawMaterial(URI.create("https://example.com/"),clock.instant(),"fixture",text.getBytes(StandardCharsets.UTF_8));}
    @Test void extractsSourceTextWithEvidenceWithoutInventingRankOrDate(){var result=new WebFieldExtractor().extract(rule(),raw("<article><h2>First</h2><a href='/one'>open</a></article><article><h2>Second</h2></article>"));assertEquals("SUCCEEDED",result.status());assertEquals(2,result.actualCount());assertEquals("/one",result.rows().getFirst().fields().get("link"));assertFalse(result.rows().getFirst().fields().containsKey("rank"));assertTrue(result.rows().getFirst().evidence().get("title").contains("h2"));}
    @Test void missingAmbiguousOrTooManyRowsAreNeverComplete(){var extractor=new WebFieldExtractor();assertEquals("PARTIAL",extractor.extract(rule(),raw("<article><h2>one</h2><h2>two</h2></article>")).status());assertEquals("FAILED",extractor.extract(rule(),raw("<p>login</p>")).status());var bounded=new WebCaptureRule(rule().url(),"article",rule().fields(),1,1,0,false);assertEquals("PARTIAL",extractor.extract(bounded,raw("<article><h2>a</h2></article><article><h2>b</h2></article>")).status());}
    @Test void formsAndScriptAreNotExtracted(){var r=new WebCaptureRule(rule().url(),"body",List.of(new WebCaptureRule.Field("text","","text",true)),1,1,0,false);var result=new WebFieldExtractor().extract(r,raw("<form>private-secret<input value='secret'></form><script>secret</script><p>Public</p>"));assertEquals("Public",result.rows().getFirst().fields().get("text"));}
    @Test void repeatedFieldsCannotAmplifyResultWithoutBound(){var fields=java.util.stream.IntStream.range(0,20).mapToObj(i->new WebCaptureRule.Field("field"+i,"","text",true)).toList();var r=new WebCaptureRule(rule().url(),"article",fields,1,100,0,false);var result=new WebFieldExtractor().extract(r,raw(("<article>"+"x".repeat(4000)+"</article>").repeat(100)));assertEquals("PARTIAL",result.status());assertTrue(result.actualCount()<100);assertTrue(result.issues().stream().anyMatch(s->s.contains("100万")));}
    @Test void rulesRejectRegexDuplicateFieldsAndCredentials(){assertThrows(CaptureException.class,()->new WebCaptureRule(rule().url(),"div:matches((a+)+)",rule().fields(),1,100,0,false));assertThrows(CaptureException.class,()->new WebCaptureRule(rule().url(),"article",List.of(rule().fields().getFirst(),rule().fields().getFirst()),1,100,0,false));for(String u:List.of("http://example.com","https://user:secret@example.com","https://example.com/?token=secret","https://localhost","https://example.com:8080/"))assertThrows(CaptureException.class,()->PublicWebUrl.syntax(u));assertThrows(CaptureException.class,()->PublicWebUrl.check("https://127.0.0.1/"));assertThrows(CaptureException.class,()->PublicWebUrl.check("https://169.254.169.254/"));}
    @Test void firecrawlMockChecksProtocolAndStatusWithoutLeakingKey()throws Exception {
        var mapper=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();var upgrades=new ArrayList<String>();
        server.createContext("/v2/scrape",exchange->{upgrades.add(Objects.toString(exchange.getRequestHeaders().getFirst("Upgrade"),""));requests.add(mapper.readTree(exchange.getRequestBody()));byte[] bytes=mapper.writeValueAsBytes(Map.of("success",true,"data",Map.of("rawHtml","<article><h2>real from fixture</h2></article>","metadata",Map.of("statusCode",200,"url","https://example.com/"))));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{String base="http://127.0.0.1:"+server.getAddress().getPort()+"/v2";var transport=new WebTransport(mapper,clock,base);var material=transport.fetch("firecrawl",rule(),new WebTransport.Provider(base,"test-secret",true,1));assertEquals("firecrawl-v2-rawHtml",material.transport());assertEquals(List.of(""),upgrades);assertEquals(0,requests.getFirst().path("maxAge").asInt());assertEquals("rawHtml",requests.getFirst().path("formats").get(0).asText());assertFalse(requests.toString().contains("test-secret"));assertFalse(material.text().contains("test-secret"));}finally{server.stop(0);}
    }
    @Test void firecrawlQuotaErrorIsStableAndDoesNotIncludeRawResponse()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v2/scrape",e->{byte[] bytes="secret-upstream-diagnostic".getBytes();e.sendResponseHeaders(402,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();try{String base="http://127.0.0.1:"+server.getAddress().getPort()+"/v2";var transport=new WebTransport(new ObjectMapper(),clock,base);var e=assertThrows(CaptureException.class,()->transport.fetch("firecrawl",rule(),new WebTransport.Provider(base,"",true,1)));assertEquals("FIRECRAWL_CREDITS_REQUIRED",e.code());assertFalse(e.getMessage().contains("secret"));}finally{server.stop(0);}
    }
}
