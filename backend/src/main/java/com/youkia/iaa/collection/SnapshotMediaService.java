package com.youkia.iaa.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.ApiException;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.util.*;

/** Derive media from hash-verified material without mutating published ranking facts. */
@Service
public class SnapshotMediaService {
 private final JdbcTemplate jdbc; private final ObjectMapper mapper;
 public SnapshotMediaService(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 public List<String> urls(){var urls=new LinkedHashSet<>(jdbc.queryForList("SELECT source_url FROM snapshot_media",String.class));for(String raw:jdbc.queryForList("SELECT entries_json FROM public_chart_observation",String.class)){try{for(var e:mapper.readTree(raw)){String url=safeUrl(e.path("thumbnail_url").asText());if(url!=null)urls.add(url);}}catch(Exception ignored){}}return List.copyOf(urls);}
 public static String safeUrl(String url){
  try { var u=URI.create(url);if("https".equals(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null)return url; }catch(Exception ignored){}
  return null;
 }
 @Transactional public Map<String,Object> extract(){return extract(0);}
 public Map<String,Object> extract(long onlySnapshot){
  int added=0;List<String> failures=new ArrayList<>();
  for(var snapshot:jdbc.queryForList("SELECT s.id,s.raw_locator,s.raw_sha256,d.adapter_key FROM ranking_snapshot s JOIN chart_definition c ON c.id=s.chart_id JOIN data_source d ON d.id=c.source_id WHERE s.status='PUBLISHED' AND d.adapter_key IN ('apple-games-public','appbrain-public')")){
   if(onlySnapshot!=0&&((Number)snapshot.get("id")).longValue()!=onlySnapshot)continue;
   try{
    String raw=Files.readString(Path.of((String)snapshot.get("raw_locator")),StandardCharsets.UTF_8);
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    if(!hash.equals(snapshot.get("raw_sha256")))throw new IllegalArgumentException("hash mismatch");
    Map<String,String> icons=new LinkedHashMap<>();
    if("apple-games-public".equals(snapshot.get("adapter_key"))){
     for(var entry:mapper.readTree(raw).path("feed").path("entry")){
      var images=entry.path("im:image");if(images.isArray()&&!images.isEmpty())icons.put(entry.path("id").path("attributes").path("im:id").asText(),images.get(images.size()-1).path("label").asText());
     }
    }else{
     var doc=Jsoup.parse(raw,"https://www.appbrain.com");
     for(var tr:doc.select("#rankings-table tbody tr")){
      var link=tr.selectFirst(".ranking-app-cell > a");var image=tr.selectFirst(".ranking-icon-cell img");
      if(link!=null&&image!=null){String href=link.attr("href");icons.put(href.substring(href.lastIndexOf('/')+1),image.absUrl("src"));}
     }
    }
    for(var version:jdbc.queryForList("SELECT v.id,v.external_id FROM ranking_entry e JOIN platform_version v ON v.id=e.platform_version_id WHERE e.snapshot_id=?",snapshot.get("id"))){
     String url=safeUrl(icons.get((String)version.get("external_id")));if(url!=null)added+=jdbc.update("INSERT IGNORE INTO snapshot_media(snapshot_id,platform_version_id,source_url,raw_sha256,extracted_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3))",snapshot.get("id"),version.get("id"),url,hash);
    }
   }catch(Exception ex){failures.add("snapshot="+snapshot.get("id")+": material unavailable or invalid");}
  }
  return Map.of("added",added,"failures",failures,"stored","source URLs from verified material; image bytes are served by original hosts");
 }
}
