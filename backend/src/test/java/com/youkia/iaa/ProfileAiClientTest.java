package com.youkia.iaa;

import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileAiClientTest {
    final ObjectMapper mapper=new ObjectMapper();
    JsonNode call(String content,int status)throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{assertEquals("Bearer SYNTHETIC-key",exchange.getRequestHeaders().getFirst("Authorization"));var request=mapper.readTree(exchange.getRequestBody());assertEquals("json_object",request.path("response_format").path("type").asText());byte[] response=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        try{return new ProfileAiClient(mapper).extract(new AiConfigService.RuntimeConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","SYNTHETIC-model","SYNTHETIC-key",5,0.2,true),mapper.valueToTree(Map.of("evidence",Map.of("excerpt","SYNTHETIC 连续原文"))));}finally{server.stop(0);}
    }
    String valid()throws Exception{return mapper.writeValueAsString(Map.of("claims",List.of(Map.of("fieldKey","summary","value","SYNTHETIC玩法描述","evidenceId",1,"quote","连续原文","locator","段落1")),"unknownFields",List.of(),"abstentionReason",""));}
    @Test void validProtocolAndSchemaProduceOnlyReferencedClaim()throws Exception{var result=call(valid(),200);ProfileAiService.validateOutput(result,Set.of("summary"),1,"SYNTHETIC 连续原文",new ProfileFieldCatalog(mapper));assertEquals(1,result.path("claims").size());}
    @Test void trustedScoreAndExtraKeysAreRejected()throws Exception{var output=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(valid());output.put("eligible",true);assertEquals("AI_SCHEMA_INVALID",assertThrows(ProfileAiClient.Failure.class,()->call(output.toString(),200)).code);}
    @Test void fabricatedQuoteAndChangedScopeAreRejected()throws Exception{var result=call(valid(),200);assertThrows(ProfileAiClient.Failure.class,()->ProfileAiService.validateOutput(result,Set.of("summary"),2,"SYNTHETIC 连续原文",new ProfileFieldCatalog(mapper)));assertThrows(ProfileAiClient.Failure.class,()->ProfileAiService.validateOutput(result,Set.of("summary"),1,"不存在引用",new ProfileFieldCatalog(mapper)));}
    @Test void providerFailureDoesNotEchoSecretOrUntrustedBody(){var failure=assertThrows(ProfileAiClient.Failure.class,()->call("SYNTHETIC-sensitive-provider-body",401));assertEquals("AI_HTTP_401",failure.getMessage());assertFalse(failure.getMessage().contains("sensitive"));}
    @Test void abstentionNeedsAllRequestedUnknownFields()throws Exception{var result=call("{\"claims\":[],\"unknownFields\":[\"summary\"],\"abstentionReason\":\"无玩法原文\"}",200);ProfileAiService.validateOutput(result,Set.of("summary"),1,"SYNTHETIC",new ProfileFieldCatalog(mapper));assertThrows(ProfileAiClient.Failure.class,()->ProfileAiService.validateOutput(result,Set.of("summary","core_loop"),1,"SYNTHETIC",new ProfileFieldCatalog(mapper)));}
    @Test void brokenJsonAndMissingCredentialsFailClosed(){assertThrows(ProfileAiClient.Failure.class,()->call("not JSON",200));var config=new AiConfigService.RuntimeConfig("http://example.com/v1","model","",5,0,true);assertEquals("AI_NOT_CONFIGURED",assertThrows(ProfileAiClient.Failure.class,()->new ProfileAiClient(mapper).extract(config,mapper.createObjectNode())).code);assertFalse(config.toString().contains("example"));}
}
