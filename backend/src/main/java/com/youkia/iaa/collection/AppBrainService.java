package com.youkia.iaa.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.ApiException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Parses only the publicly visible HTML table, preserving gaps and source date. */
@Service
public class AppBrainService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path materials;
    public AppBrainService(JdbcTemplate jdbc,ObjectMapper mapper,@Value("${app.material-dir:./data/materials}") String materialDir){this.jdbc=jdbc;this.mapper=mapper;materials=Path.of(materialDir).toAbsolutePath().normalize();}
    public record Row(int rank,String packageName,String name,String developer,String sourcePage,BigDecimal rating,String installs,String recent) {}
    public record Material(String country,String url,LocalDate date,String hash,List<Row> rows,String raw) {}
    public Material validate(String country,String raw) {
        if(!Set.of("us","gb","de","fr").contains(country))throw invalid("INVALID_COUNTRY","只接受Google Play美英德法");
        if(raw==null||raw.isBlank()||raw.length()>4_000_000)throw invalid("INVALID_HTML","HTML为空或超过4MB");
        try {
            String url="https://www.appbrain.com/stats/google-play-rankings/top_free/game/"+country;
            var doc=Jsoup.parse(raw,url);
            if(!url.equals(doc.select("link[rel=canonical]").attr("href")))throw invalid("SOURCE_SCOPE_MISMATCH","来源canonical不匹配国家、商店或游戏免费榜");
            LocalDate date=LocalDate.parse(doc.select(".stats-pagetop-updated time").text(),DateTimeFormatter.ofPattern("MMMM d, uuuu",Locale.ENGLISH));
            if(date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw invalid("SOURCE_DATE_INVALID","来源日不应在未来");
            List<Row> rows=new ArrayList<>();Set<Integer> ranks=new HashSet<>();Set<String> packages=new HashSet<>();
            for(Element tr:doc.select("#rankings-table tbody tr")) {
                if(tr.select(".ranking-rank").isEmpty())continue;
                int rank=Integer.parseInt(tr.select(".ranking-rank").text());
                if(rank>100)continue;
                Element link=tr.selectFirst(".ranking-app-cell > a");if(link==null)throw invalid("MISSING_ID","排名条目缺少应用链接");
                String href=link.attr("href"),pkg=href.substring(href.lastIndexOf('/')+1);
                if(rank<1||!ranks.add(rank)||!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")||!packages.add(pkg)||link.text().isBlank())throw invalid("INVALID_ENTRY","排名、包名、名称无效或重复");
                String ratingText=tr.select(".ranking-rating-cell > span").text();
                BigDecimal rating=ratingText.isBlank()?null:new BigDecimal(ratingText);
                if(rating!=null&&(rating.signum()<0||rating.compareTo(BigDecimal.valueOf(5))>0))throw invalid("INVALID_RATING","评分超出0至5");
                var cells=tr.select("td");if(cells.size()!=7)throw invalid("COLUMN_CHANGED","来源表格列数发生变化，停止发布");
                rows.add(new Row(rank,pkg,link.text(),tr.select(".ranking-app-cell-creator a").text(),link.absUrl("href"),rating,cells.get(5).text(),cells.get(6).text()));
            }
            if(rows.isEmpty())throw invalid("NO_PUBLIC_ROWS","未发现公开榜单行；可能为登录/拦截页面");
            rows.sort(Comparator.comparingInt(Row::rank));return new Material(country,url,date,sha(raw),rows,raw);
        }catch(ApiException e){throw e;}catch(Exception e){throw invalid("INVALID_HTML","AppBrain页面结构或日期无法校验");}
    }
    @Transactional
    public Map<String,Object> ingest(String country,String raw) {
        Material m=validate(country,raw);long source=source();long chart=chart(source,country);
        jdbc.queryForObject("SELECT id FROM chart_definition WHERE id=? FOR UPDATE",Long.class,chart);
        var prior=jdbc.queryForList("SELECT id FROM ranking_snapshot WHERE chart_id=? AND business_date=? AND raw_sha256=?",Long.class,chart,m.date(),m.hash());
        if(!prior.isEmpty())return result(prior.getFirst(),chart,m,true);
        Path file=materials.resolve(m.hash()+".html");
        try{Files.createDirectories(materials);if(!Files.exists(file))Files.writeString(file,raw,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);if(!sha(Files.readString(file,StandardCharsets.UTF_8)).equals(m.hash()))throw new IllegalStateException();}
        catch(Exception e){throw invalid("MATERIAL_STORE_FAILED","原始HTML保存或哈希校验失败");}
        var jobs=jdbc.queryForList("SELECT id FROM collection_job WHERE source_id=?",Long.class,source);
        long job=jobs.isEmpty()?insert("INSERT INTO collection_job(source_id,name,schedule_spec,timezone,enabled,config,version) VALUES(?,'AppBrain public manual','manual','Asia/Shanghai',FALSE,'{}',0)",source):jobs.getFirst();
        long run=insert("INSERT INTO collection_run(job_id,business_date,run_mode,status,stage,idempotency_key,started_at,heartbeat_at,model_metadata) VALUES(?,?,'LIVE_PUBLIC','RUNNING','VALIDATED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?)",job,m.date(),"appbrain:"+chart+":"+m.date()+":"+m.hash(),"{\"parser\":\"appbrain-html-v1\",\"provider\":\"none\"}");
        int revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM ranking_snapshot WHERE chart_id=? AND business_date=?",Integer.class,chart,m.date());
        long snapshot=insert("INSERT INTO ranking_snapshot(chart_id,run_id,business_date,source_date,revision,status,completeness,requested_top_n,covered_top_n,actual_count,source_url,raw_locator,raw_sha256,captured_at,published_at) VALUES(?,?,?,?,?,'PUBLISHED',?,100,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",chart,run,m.date(),m.date(),revision,complete(m),m.rows().getLast().rank(),m.rows().size(),m.url(),file.toString(),m.hash());
        for(Row row:m.rows()){
            var versions=jdbc.queryForList("SELECT id FROM platform_version WHERE platform='GOOGLE_PLAY' AND external_id=?",Long.class,row.packageName());
            long version;
            String store="https://play.google.com/store/apps/details?id="+row.packageName();
            if(versions.isEmpty()){
                long product=insert("INSERT INTO product(name,pool_state,first_observed_at,created_at,updated_at,version) VALUES(?,'PENDING_IDENTIFICATION',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0)",row.name());
                version=insert("INSERT INTO platform_version(product_id,platform,external_id,name,developer,package_name,entry_url,first_observed_at,source_fields,created_at,updated_at) VALUES(?,'GOOGLE_PLAY',?,?,?,?,?,UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",product,row.packageName(),row.name(),row.developer(),row.packageName(),store,"{\"source\":\"APPBRAIN_PUBLIC\",\"category\":\"GAME\"}");
            }else version=versions.getFirst();
            var metrics=new LinkedHashMap<String,Object>();metrics.put("source","APPBRAIN_PUBLIC");metrics.put("name",row.name());metrics.put("summary",row.name());metrics.put("summaryKind","TITLE_ONLY");metrics.put("developer",row.developer());metrics.put("storeUrl",store);metrics.put("sourceUpdated",m.date().toString());metrics.put("installsDisplay",row.installs());metrics.put("installsMethod","Source display; Google Play install category, not exact sales");metrics.put("recentInstallsDisplay",row.recent());metrics.put("recentPeriodDays",30);metrics.put("recentInstallsEstimated",true);metrics.put("monetization","UNKNOWN");
            metrics.put("metricDefinitions",Map.of("rank",Map.of("kind","STORE_FREE_RANK","estimated",false,"region",country.toUpperCase(Locale.ROOT),"date",m.date().toString()),"recentInstalls",Map.of("estimated",true,"unit","installs","windowDays",30,"rawDisplay",row.recent(),"method","第三方页面估算，不能推算收入或日增长"),"rating",Map.of("scale",5,"ratingCountAvailable",false)));
            jdbc.update("INSERT INTO ranking_entry(snapshot_id,platform_version_id,rank_position,rating,source_metrics) VALUES(?,?,?,?,?)",snapshot,version,row.rank(),row.rating(),json(metrics));
            jdbc.update("INSERT INTO evidence(platform_version_id,source_id,kind,source_url,source_record_id,locator,raw_sha256,excerpt,observed_at,scope,metadata,created_by,created_at) VALUES(?,?,'PUBLIC_GAME_RANKING',?,?,?,?,?,UTC_TIMESTAMP(3),?,?,'appbrain-public',UTC_TIMESTAMP(3))",version,source,m.url(),row.packageName(),"#rankings-table .ranking-rank = "+row.rank(),m.hash(),row.name()+" #"+row.rank()+"; rating="+row.rating()+"; recent installs (30d estimated)="+row.recent(),json(Map.of("snapshotId",String.valueOf(snapshot),"region",country.toUpperCase(Locale.ROOT),"businessDate",m.date().toString())),json(Map.of("rawLocator",file.toString(),"noIaa",true,"transport","submitted-public-html","sourceDatePolicy","AppBrain displayed update day; timezone unspecified","authorization","Public page access verified; commercial redistribution rights unverified")));
        }
        jdbc.update("UPDATE collection_run SET status='SUCCEEDED',stage='PUBLISHED',finished_at=UTC_TIMESTAMP(3),metrics=? WHERE id=?",json(Map.of("entries",m.rows().size(),"completeness",complete(m))),run);
        new SnapshotMediaService(jdbc,mapper).extract(snapshot);
        return result(snapshot,chart,m,false);
    }
    private String complete(Material m){return m.rows().size()==100?"COMPLETE":"PARTIAL";}
    private Map<String,Object> result(long snapshot,long chart,Material m,boolean reused){return Map.of("snapshotId",String.valueOf(snapshot),"chartId",String.valueOf(chart),"sourceUrl",m.url(),"businessDate",m.date(),"entries",m.rows().size(),"coveredTopN",m.rows().getLast().rank(),"completeness",complete(m),"reused",reused,"rawSha256",m.hash(),"monetization","UNKNOWN");}
    private long source(){var ids=jdbc.queryForList("SELECT id FROM data_source WHERE adapter_key='appbrain-public'",Long.class);return ids.isEmpty()?insert("INSERT INTO data_source(name,adapter_key,access_method,enabled,config,authorization_notes,version,created_at,updated_at) VALUES('AppBrain公开Google Play排行榜','appbrain-public','PUBLIC_WEB',TRUE,'{}',?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))","公开页面免费验证；付费CSV/API未购买；商业再分发授权未确认；不提供纯IAA结论"):ids.getFirst();}
    private long chart(long source,String country){var ids=jdbc.queryForList("SELECT id FROM chart_definition WHERE source_id=? AND region=?",Long.class,source,country.toUpperCase(Locale.ROOT));return ids.isEmpty()?insert("INSERT INTO chart_definition(source_id,platform,region,external_chart_id,name,kind,category_key,device_scope,requested_top_n,enabled,methodology,config) VALUES(?,'GOOGLE_PLAY',?,'top_free/game',?,'HEAT','GAME','DEFAULT',100,TRUE,?,'{}')",source,country.toUpperCase(Locale.ROOT),"AppBrain Google Play游戏免费榜", "第三方公开榜单；来源显示更新日时区未声明，原样保存；保留缺行；Recent为近30日估算安装量；不等于IAA收益"):ids.getFirst();}
    private long insert(String sql,Object...args){var keys=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},keys);return Objects.requireNonNull(keys.getKey()).longValue();}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String sha(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ApiException invalid(String code,String message){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,code,message);}
}
