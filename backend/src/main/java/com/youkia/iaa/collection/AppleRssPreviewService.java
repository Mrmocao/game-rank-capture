package com.youkia.iaa.collection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Public Apple RSS preview. It never writes snapshots and never infers IAA. */
@Service
public class AppleRssPreviewService {
    private final RestClient client=RestClient.builder().baseUrl("https://rss.applemarketingtools.com").defaultHeader("User-Agent","iaa-radar-public-source/1.0").defaultHeader("Accept","application/json").build();
    private final ObjectMapper mapper;
    public AppleRssPreviewService(ObjectMapper mapper){this.mapper=mapper;}
    public Map<String,Object> preview(String country,Integer requested){String cc=country==null?"cn":country.toLowerCase(Locale.ROOT);if(!Set.of("cn","us","gb","de","fr").contains(cc))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_COUNTRY","只支持 cn/us/gb/de/fr");int limit=requested==null?10:Math.min(Math.max(requested,1),100);String body;try{body=client.get().uri("/api/v2/{country}/apps/top-free/{limit}/apps.json",cc,limit).retrieve().body(String.class);}catch(Exception e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"APPLE_RSS_UNAVAILABLE","Apple RSS 当前不可访问");}try{if(body==null||body.isBlank())throw new IllegalArgumentException("empty response");JsonNode root=mapper.readTree(body), feed=root.path("feed"), results=feed.path("results");List<Map<String,Object>> items=new ArrayList<>();int rank=1;for(JsonNode n:results){Map<String,Object> item=new LinkedHashMap<>();item.put("rank",rank++);item.put("externalId",n.path("id").asText(null));item.put("name",n.path("name").asText(null));item.put("developer",n.path("artistName").asText(null));item.put("releaseDate",n.path("releaseDate").asText(null));item.put("url",n.path("url").asText(null));item.put("genres",n.path("genres"));item.put("monetization","UNKNOWN");item.put("evidence","Public Apple RSS ranking only; no IAA evidence");items.add(item);}Map<String,Object> out=new LinkedHashMap<>();out.put("mode","PUBLIC_PREVIEW");out.put("source","Apple Marketing Tools RSS");out.put("country",cc);out.put("chartName",feed.path("title").asText("Top Free Apps"));out.put("requestedTopN",limit);out.put("coveredTopN",items.size());out.put("items",items);out.put("persisted",false);out.put("warning","公开免费 App 榜预览，不等于完整游戏榜；不提供 PURE_IAA 结论");return out;}catch(Exception e){throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"APPLE_RSS_INVALID","Apple RSS 响应无法解析");}}
}
