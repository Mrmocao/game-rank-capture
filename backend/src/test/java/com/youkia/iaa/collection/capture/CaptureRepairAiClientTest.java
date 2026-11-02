package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.CaptureException;
import com.youkia.iaa.service.AiConfigService.RuntimeConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptureRepairAiClientTest {
    final ObjectMapper mapper=new ObjectMapper();
    final CaptureRepairAiClient client=new CaptureRepairAiClient(mapper);
    @Test void mockedProviderReceivesIndependentProtocolAndStrictProposalIsAccepted() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var received=new ArrayList<JsonNode>();
        server.createContext("/v1/chat/completions",exchange->{
            received.add(mapper.readTree(exchange.getRequestBody()));
            String proposal="{\"diagnosis\":\"结构变更\",\"changes\":[\"检查榜单容器选择器\"],\"checks\":[\"验证连续Top100\"],\"requiresHumanLogin\":false}";
            byte[] body=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",proposal)))));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var result=client.diagnose(new RuntimeConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture","test-only",5,0.1,true),Map.of("errorCode","PARSE_FAILED"));
            assertEquals("结构变更",result.path("diagnosis").asText());assertTrue(received.getFirst().path("messages").path(0).path("content").asText().contains("不可信数据"));assertFalse(received.getFirst().toString().contains("test-only"));
        }finally{server.stop(0);}
    }
    @Test void schemaRejectsExecutableAndOversizedSuggestions() throws Exception {
        assertThrows(CaptureException.class,()->client.validate(mapper.readTree("{\"diagnosis\":\"test\",\"changes\":[],\"checks\":[],\"requiresHumanLogin\":false,\"script\":\"run\"}")));
        assertThrows(CaptureException.class,()->client.validate(mapper.valueToTree(Map.of("diagnosis","x".repeat(3001),"changes",List.of(),"checks",List.of(),"requiresHumanLogin",false))));
        assertThrows(CaptureException.class,()->client.validate(mapper.valueToTree(Map.of("diagnosis","ok","changes",List.of(Map.of("code","run")),"checks",List.of(),"requiresHumanLogin",false))));
    }
    @Test void missingConfigAndCredentialUrlsFailBeforeNetwork() {
        assertEquals("AI_NOT_CONFIGURED",assertThrows(CaptureException.class,()->client.diagnose(new RuntimeConfig("","","",5,0,true),Map.of())).code());
        assertEquals("AI_URL_INVALID",assertThrows(CaptureException.class,()->client.diagnose(new RuntimeConfig("https://secret@example.org/v1","model","key",5,0,true),Map.of())).code());
    }
    @Test void providerErrorsNeverExposeResponseBodyOrKey() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{byte[] body="private diagnostic secret-token".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(401,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {var e=assertThrows(CaptureException.class,()->client.diagnose(new RuntimeConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture","secret-token",5,0,true),Map.of()));assertEquals("AI_HTTP_FAILED",e.code());assertFalse(e.getMessage().contains("secret"));}finally{server.stop(0);}
    }
}
