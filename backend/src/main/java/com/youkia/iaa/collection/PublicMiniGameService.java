package com.youkia.iaa.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.ApiException;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class PublicMiniGameService {
 public static final String SOURCE="https://rank.gravity-engine.com/#/";
 private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final Path materials;
 public PublicMiniGameService(JdbcTemplate jdbc,ObjectMapper mapper,@Value("${app.material-dir:./data/materials}") String path){this.jdbc=jdbc;this.mapper=mapper;this.materials=Path.of(path).toAbsolutePath();}
 public record Material(LocalDate date,String hash,List<Map<String,Object>> entries){}
 public Material validate(String platform,String raw){
  if(!Set.of("WECHAT","DOUYIN").contains(platform)||raw==null||raw.length()>4_000_000)throw invalid();
  try{
   var doc=Jsoup.parse(raw,SOURCE);String expected=platform.equals("WECHAT")?"微信小游戏":"抖音小游戏";
   if(!expected.equals(doc.select(".platform-tab.active").text())||!"日榜".equals(doc.select(".rank-period-switch__button.active").text()))throw invalid();
   String dateText=doc.select("input[value]").stream().map(e->e.attr("value")).filter(v->v.matches("\\d{4}-\\d{2}-\\d{2}.*")).findFirst().orElseThrow();
   LocalDate date=LocalDate.parse(dateText.substring(0,10));if(date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw invalid();
   String title=platform.equals("WECHAT")?"人气榜":"热门榜";
   var card=doc.select(".rank-card").stream().filter(e->title.equals(e.select(".rank-card-title").text())).findFirst().orElseThrow();
   var rows=card.select(".rank-child-item");if(rows.isEmpty()||rows.size()>20)throw invalid();
   List<Map<String,Object>> entries=new ArrayList<>();int rank=0;
   for(var row:rows){rank++;String name=row.select(".rank-app-name").text();if(name.isBlank())throw invalid();
    // The podium uses rank images for 1–3; rows 4+ display explicit NO.n. Preserve the source's order.
    if(rank>3&&!row.select(".rank-left-item").text().replace(" ","").equals("NO."+rank))throw invalid();
    Map<String,Object> e=new LinkedHashMap<>();e.put("rank",rank);e.put("product_name",name);e.put("developer",row.select(".app-publisher__name").text());e.put("tags",row.select(".rank-tags-row .categorize").eachText());e.put("thumbnail_url",SnapshotMediaService.safeUrl(row.select(".rank-app-icon img").attr("src")));e.put("completeness","PARTIAL");e.put("identityState","UNVERIFIED");e.put("monetization","UNKNOWN");e.put("locator",".rank-card["+title+"] .rank-child-item["+(rank-1)+"]");entries.add(e);
   }
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));return new Material(date,hash,entries);
  }catch(ApiException e){throw e;}catch(Exception e){throw invalid();}
 }
 @Transactional public Map<String,Object> ingest(String platform,String raw){
  Material m=validate(platform,raw);try{Files.createDirectories(materials);Path file=materials.resolve(m.hash()+".html");if(!Files.exists(file))Files.writeString(file,raw,StandardCharsets.UTF_8);if(!raw.equals(Files.readString(file)))throw invalid();
   int added=jdbc.update("INSERT IGNORE INTO public_chart_observation(platform,board_type,business_date,source_url,raw_sha256,raw_locator,entries_json,actual_count,captured_at) VALUES(?,'POPULARITY',?,?,?,?,?,?,UTC_TIMESTAMP(3))",platform,m.date(),SOURCE,m.hash(),file.toString(),mapper.writeValueAsString(m.entries()),m.entries().size());
   return Map.of("platform",platform,"date",m.date(),"entries",m.entries().size(),"completeness","PARTIAL","reused",added==0,"rawSha256",m.hash(),"identityState","UNVERIFIED");
  }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"MATERIAL_STORE_FAILED","公开榜单材料存储失败");}
 }
 public Map<String,Object> query(String platform,LocalDate date){
  if(!Set.of("WECHAT","DOUYIN").contains(platform))throw invalid();
  var rows=jdbc.queryForList("SELECT id,entries_json,raw_sha256,captured_at FROM public_chart_observation WHERE platform=? AND board_type='POPULARITY' AND business_date=? ORDER BY captured_at DESC,id DESC LIMIT 1",platform,date);
  Map<String,Object> out=new LinkedHashMap<>();out.put("platform",platform);out.put("date",date);out.put("sourceUrl",SOURCE);out.put("identityState","UNVERIFIED");out.put("notice","公开前20条；平台身份未核验，仅用于榜单观察，未进入产品库或正式精选。");out.put("completeness",rows.isEmpty()?"MISSING_SNAPSHOT":"PARTIAL");out.put("items",List.of());
  if(!rows.isEmpty()){try{var entries=mapper.readValue((String)rows.getFirst().get("entries_json"),List.class);for(Object obj:entries){var entry=(Map<String,Object>)obj;entry.put("thumbnail_url",com.youkia.iaa.service.IconCache.display(entry.get("thumbnail_url")));}out.put("items",entries);out.put("materialId",rows.getFirst().get("id"));out.put("rawSha256",rows.getFirst().get("raw_sha256"));}catch(Exception e){throw new IllegalStateException(e);}}
  return out;
 }
 private static ApiException invalid(){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PUBLIC_RANKING","小游戏材料平台、日期、日榜或公开排名无法核验");}
}
