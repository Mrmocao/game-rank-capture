package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;

/** Computes only within a chart's exact calendar dates; never synthesizes a missing rank. */
public final class MarketInsightService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MarketWeeklySkill skill;
    public MarketInsightService(JdbcTemplate jdbc,ObjectMapper mapper,MarketWeeklySkill skill){this.jdbc=jdbc;this.mapper=mapper;this.skill=skill;}
    public record Analysis(List<Map<String,Object>> candidates,List<Map<String,Object>> sources,List<Map<String,Object>> publicSources){}
    public Analysis analyze(LocalDate day) {
        return analyze(day,java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }
    public Analysis analyze(LocalDate day,java.time.LocalDateTime availableCutoff) {
        var snapshots=jdbc.queryForList("""
            SELECT s.id snapshot_id,s.chart_id,s.business_date,s.revision,s.completeness,s.actual_count,
              s.raw_sha256,s.source_url,s.raw_locator,c.platform,c.region,c.name chart_name,ds.name source_name
            FROM ranking_snapshot s JOIN chart_definition c ON c.id=s.chart_id
            JOIN data_source ds ON ds.id=c.source_id AND ds.access_method<>'SIMULATED'
            JOIN collection_run cr ON cr.id=s.run_id AND cr.run_mode<>'DEMO'
            WHERE c.kind='HEAT' AND c.category_key='GAME' AND s.status='PUBLISHED'
              AND ((c.platform IN ('IOS','WECHAT','DOUYIN') AND c.region='CN') OR (c.platform='GOOGLE_PLAY' AND c.region IN ('US','GB','DE','FR')))
              AND s.business_date BETWEEN ? AND ? AND s.published_at<=?
              AND NOT EXISTS (SELECT 1 FROM ranking_snapshot n WHERE n.chart_id=s.chart_id AND n.business_date=s.business_date AND n.status='PUBLISHED' AND n.revision>s.revision AND n.published_at<=?)
            ORDER BY s.chart_id,s.business_date
            """,day.minusDays(13),day,availableCutoff,availableCutoff);
        Map<Long,Map<String,Object>> byId=new LinkedHashMap<>();Map<String,Map<String,Object>> byDay=new HashMap<>();
        for(var s:snapshots){byId.put(num(s,"snapshot_id"),s);byDay.put(s.get("chart_id")+":"+s.get("business_date"),s);}
        List<Map<String,Object>> entries=new ArrayList<>();
        if(!byId.isEmpty()) {
            String ids=String.join(",",Collections.nCopies(byId.size(),"?"));
            entries=jdbc.queryForList("""
                SELECT e.snapshot_id,e.platform_version_id version_id,e.rank_position,e.rating,e.rating_count,e.source_metrics,
                  v.product_id,v.platform,v.name version_name,p.pool_state,
                  EXISTS(SELECT 1 FROM selection_record sel WHERE sel.product_id=p.id AND sel.status IN ('IN_PROGRESS','COMPLETED')) already_producing,
                  (SELECT source_url FROM snapshot_media sm WHERE sm.snapshot_id=e.snapshot_id AND sm.platform_version_id=v.id) thumbnail_source_url
                FROM ranking_entry e JOIN platform_version v ON v.id=e.platform_version_id JOIN product p ON p.id=v.product_id
                WHERE e.snapshot_id IN (
                """+ids+") ORDER BY e.snapshot_id,v.id",byId.keySet().toArray());
        }
        Map<String,Map<String,Object>> lookup=new HashMap<>();
        for(var e:entries)lookup.put(e.get("snapshot_id")+":"+e.get("version_id"),e);
        Map<String,Map<String,Object>> latestEntries=new LinkedHashMap<>();
        for(var e:entries){var s=byId.get(num(e,"snapshot_id"));String key=s.get("chart_id")+":"+e.get("version_id");var old=latestEntries.get(key);
            if(old==null||String.valueOf(s.get("business_date")).compareTo(String.valueOf(byId.get(num(old,"snapshot_id")).get("business_date")))>=0)latestEntries.put(key,e);}
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(var e:latestEntries.values()){var s=byId.get(num(e,"snapshot_id"));
            Map<String,Object> c=new LinkedHashMap<>(s);c.putAll(e);
            try {var m=mapper.readTree(String.valueOf(e.get("source_metrics")));
                for(var field:Map.of("product_name","name","summary","summary","developer","developer","store_url","storeUrl","recent_installs","recentInstallsDisplay","summary_kind","summaryKind").entrySet())c.put(field.getKey(),m.path(field.getValue()).isMissingNode()?null:m.path(field.getValue()).asText());
            }catch(Exception ignored){}
            if(c.get("product_name")==null)c.put("product_name",e.get("version_name"));
            c.put("matched_keywords",skill.keywords(String.valueOf(c.get("product_name"))+" "+String.valueOf(c.get("summary"))));
            String sourceText=(String.valueOf(c.get("product_name"))+" "+String.valueOf(c.get("summary"))).toLowerCase(Locale.ROOT);
            List<String> complexity=new ArrayList<>();skill.policy.path("complexityKeywords").forEach(k->{if(sourceText.contains(k.asText()))complexity.add(k.asText());});c.put("complexity_flags",complexity);
            List<Map<String,Object>> history=new ArrayList<>();
            for(int offset=13;offset>=0;offset--){LocalDate d=day.minusDays(offset);var snap=byDay.get(s.get("chart_id")+":"+d);Map<String,Object> point=new LinkedHashMap<>();point.put("date",d.toString());
                var entry=snap==null?null:lookup.get(snap.get("snapshot_id")+":"+e.get("version_id"));
                point.put("rank",entry==null?null:entry.get("rank_position"));point.put("ratingCount",entry==null?null:entry.get("rating_count"));
                point.put("state",snap==null?"MISSING_SNAPSHOT":entry!=null?"OBSERVED":"COMPLETE".equals(snap.get("completeness"))?"NOT_IN_COVERAGE":"PARTIAL_UNKNOWN");
                if(snap!=null){for(String key:List.of("snapshot_id","revision","completeness","raw_sha256","source_url","raw_locator"))point.put(key,snap.get(key));}
                history.add(point);
            }
            c.put("lastObservedDate",String.valueOf(s.get("business_date")));c.put("business_date",day);
            c.put("rank_position",history.getLast().get("rank"));c.put("history",history);candidates.add(c);
        }
        var sources=jdbc.queryForList("""
            SELECT c.id chart_id,c.name,c.platform,c.region,ds.name source_name
            FROM chart_definition c JOIN data_source ds ON ds.id=c.source_id
            WHERE c.enabled=TRUE AND c.kind='HEAT' AND c.category_key='GAME' AND ds.access_method<>'SIMULATED'
              AND ((c.platform IN ('IOS','WECHAT','DOUYIN') AND c.region='CN') OR (c.platform='GOOGLE_PLAY' AND c.region IN ('US','GB','DE','FR')))
            ORDER BY c.id
            """);
        for(var source:sources){List<String> missing=new ArrayList<>();int count=0;
            for(int off=13;off>=0;off--){String d=day.minusDays(off).toString();if(byDay.containsKey(source.get("chart_id")+":"+d))count++;else missing.add(d);}
            source.put("observedDays",count);source.put("missingDates",missing);source.put("windowDays",14);
            var current=byDay.get(source.get("chart_id")+":"+day);source.put("currentState",current==null?"MISSING_SNAPSHOT":current.get("completeness"));source.put("actualCount",current==null?0:current.get("actual_count"));
            source.put("delta7dReady",current!=null&&byDay.containsKey(source.get("chart_id")+":"+day.minusDays(7)));
        }
        var publicSources=jdbc.queryForList("""
            SELECT p.id material_id,p.platform,p.business_date,p.actual_count,p.raw_sha256,p.source_url
            FROM public_chart_observation p WHERE p.business_date=? AND p.board_type='POPULARITY' AND p.captured_at<=?
             AND NOT EXISTS(SELECT 1 FROM public_chart_observation n WHERE n.platform=p.platform AND n.board_type=p.board_type AND n.business_date=p.business_date AND n.captured_at<=? AND (n.captured_at>p.captured_at OR (n.captured_at=p.captured_at AND n.id>p.id))) ORDER BY p.platform
            """,day,availableCutoff,availableCutoff);
        for(var p:publicSources){p.put("state","IDENTITY_UNVERIFIED");p.put("reason","公开前20观察；稳定身份未核验，不计算跨日增长或合并产品");}
        return new Analysis(candidates,sources,publicSources);
    }
    public static long num(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
}