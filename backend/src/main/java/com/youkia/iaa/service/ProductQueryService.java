package com.youkia.iaa.service;

import com.youkia.iaa.api.ApiException;
import com.youkia.iaa.domain.BusinessDate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class ProductQueryService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock = Clock.systemUTC();
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    public ProductQueryService(JdbcTemplate jdbc) { this.jdbc=jdbc; this.named=new NamedParameterJdbcTemplate(jdbc); }

    public LocalDate date(String value) { return BusinessDate.parseOrToday(value, clock, zone); }

    public LocalDate latestAvailableDate() {
        try { return jdbc.queryForObject("SELECT MAX(business_date) FROM ranking_snapshot WHERE status='PUBLISHED' AND business_date <= ?", LocalDate.class, LocalDate.now(clock.withZone(zone))); }
        catch (EmptyResultDataAccessException e) { return null; }
    }

    public Map<String,Object> coverage(String value) {
        LocalDate date = value == null || value.isBlank() ? latestAvailableDate() : date(value);
        if (date == null) date=LocalDate.now(clock.withZone(zone));
        List<Map<String,Object>> charts = jdbc.queryForList("""
            SELECT c.id,c.platform,c.region,c.name,c.kind,c.requested_top_n,c.enabled,
                   CASE WHEN c.name LIKE '%人气%' OR c.name LIKE '%热门%' THEN 'POPULARITY' WHEN c.name LIKE '%免费%' THEN 'FREE' ELSE c.kind END chart_type,
                   s.completeness,s.covered_top_n,s.actual_count,
                   CASE WHEN s.id IS NULL THEN 'MISSING_SNAPSHOT' ELSE s.completeness END AS data_state
            FROM chart_definition c JOIN data_source ds ON ds.id=c.source_id LEFT JOIN ranking_snapshot s ON s.chart_id=c.id AND s.business_date=? AND s.status='PUBLISHED'
              AND s.revision=(SELECT MAX(s2.revision) FROM ranking_snapshot s2 WHERE s2.chart_id=c.id AND s2.business_date=? AND s2.status='PUBLISHED')
            WHERE c.enabled=TRUE AND ds.access_method<>'SIMULATED' ORDER BY c.platform,c.region,c.id""", date,date);
        boolean any = charts.stream().anyMatch(c -> c.get("data_state") != null && !"MISSING_SNAPSHOT".equals(c.get("data_state")));
        boolean all = !charts.isEmpty() && charts.stream().allMatch(c -> "COMPLETE".equals(c.get("data_state")));
        Map<String,Object> out=new LinkedHashMap<>(); out.put("date",date); out.put("latestAvailableDate",latestAvailableDate()); out.put("businessTimezone",zone.getId()); out.put("coverageState", all ? "COMPLETE" : any ? "PARTIAL" : "NONE"); out.put("charts",charts); return out;
    }

    public Map<String,Object> list(String value, String q, String platform, String region, Long chartId, String poolState, String selectionState, int page, int pageSize) {
        LocalDate date = value == null || value.isBlank() ? latestAvailableDate() : date(value);
        if (date == null) date=LocalDate.now(clock.withZone(zone));
        page=Math.max(page,1); pageSize=Math.min(Math.max(pageSize,1),100);
        StringBuilder where=new StringBuilder(" WHERE EXISTS(SELECT 1 FROM platform_version realv WHERE realv.product_id=p.id AND realv.external_id NOT LIKE 'demo-%') AND p.first_observed_at < :cutoff AND EXISTS (SELECT 1 FROM ranking_snapshot visible WHERE visible.business_date=:date AND visible.status='PUBLISHED') ");
        MapSqlParameterSource p=new MapSqlParameterSource("date",date).addValue("cutoff",date.plusDays(1).atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        if (q!=null && !q.isBlank()) { where.append(" AND p.name LIKE :q "); p.addValue("q","%"+q.trim()+"%"); }
        if (poolState!=null && !poolState.isBlank()) { where.append(" AND p.pool_state=:poolState "); p.addValue("poolState",poolState); }
        if (selectionState!=null && !selectionState.isBlank()) {
            if ("UNSELECTED".equals(selectionState)) where.append(" AND NOT EXISTS (SELECT 1 FROM selection_record sx WHERE sx.product_id=p.id) ");
            else { where.append(" AND EXISTS (SELECT 1 FROM selection_record sx WHERE sx.product_id=p.id AND sx.status=:selectionState) "); p.addValue("selectionState",selectionState); }
        }
        if (platform!=null && !platform.isBlank()) { where.append(" AND EXISTS (SELECT 1 FROM platform_version fv WHERE fv.product_id=p.id AND fv.platform=:platform) "); p.addValue("platform",platform); }
        if (region!=null && !region.isBlank() && chartId==null) { where.append(" AND EXISTS (SELECT 1 FROM platform_version rv JOIN ranking_entry region_entry ON region_entry.platform_version_id=rv.id JOIN ranking_snapshot region_snapshot ON region_snapshot.id=region_entry.snapshot_id JOIN chart_definition rc ON rc.id=region_snapshot.chart_id WHERE rv.product_id=p.id AND rc.region=:region AND region_snapshot.status='PUBLISHED' AND region_snapshot.business_date<=:date "); if(platform!=null&&!platform.isBlank())where.append(" AND rv.platform=:platform ");where.append(") ");p.addValue("region",region); }
        if (chartId!=null) { where.append(" AND EXISTS (SELECT 1 FROM ranking_entry rx JOIN ranking_snapshot rsx ON rsx.id=rx.snapshot_id WHERE rsx.chart_id=:chartId AND rsx.business_date=:date AND rsx.status='PUBLISHED' AND rsx.revision=(SELECT MAX(r3.revision) FROM ranking_snapshot r3 WHERE r3.chart_id=:chartId AND r3.business_date=:date AND r3.status='PUBLISHED') AND rx.platform_version_id IN (SELECT id FROM platform_version WHERE product_id=p.id)) "); p.addValue("chartId",chartId); }
        Integer total=named.queryForObject("SELECT COUNT(*) FROM product p"+where,p,Integer.class);
        String sql="""
                SELECT p.id,p.name,p.pool_state,p.first_observed_at,
                (SELECT sm.source_url FROM snapshot_media sm JOIN ranking_snapshot rs ON rs.id=sm.snapshot_id JOIN platform_version rv ON rv.id=sm.platform_version_id WHERE rv.product_id=p.id AND rs.business_date=:date AND rs.status='PUBLISHED' ORDER BY rs.revision DESC,rs.id DESC LIMIT 1) thumbnail_url,
                COALESCE(s.total,0) selection_total,COALESCE(s.in_progress,0) selection_in_progress,COALESCE(s.completed,0) selection_completed
                FROM product p LEFT JOIN (SELECT product_id,COUNT(*) total,
                SUM(CASE WHEN status='IN_PROGRESS' THEN 1 ELSE 0 END) in_progress,
                SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END) completed FROM selection_record GROUP BY product_id) s ON s.product_id=p.id
                """+where+" ORDER BY p.first_observed_at DESC,p.id LIMIT :limit OFFSET :offset";
        p.addValue("limit",pageSize); p.addValue("offset",(long)(page-1)*pageSize);
        List<Map<String,Object>> rows=named.queryForList(sql,p); List<Map<String,Object>> items=new ArrayList<>();
        for (Map<String,Object> row: rows) {
            Map<String,Object> item=new LinkedHashMap<>(); item.put("id",String.valueOf(row.get("id"))); item.put("name",row.get("name")); item.put("poolState",row.get("pool_state")); item.put("firstObservedDate",row.get("first_observed_at")); item.put("thumbnailUrl",IconCache.display(row.get("thumbnail_url"))); item.put("stateAsOf",date); item.put("rankings",rankingRows(((Number)row.get("id")).longValue(),date));
            Map<String,Object> summary=new LinkedHashMap<>(); summary.put("scope","CURRENT"); summary.put("total",row.get("selection_total")); summary.put("inProgress",row.get("selection_in_progress")); summary.put("completed",row.get("selection_completed")); item.put("selectionSummary",summary); items.add(item);
        }
        Map<String,Object> data=new LinkedHashMap<>(); data.put("date",date); data.put("businessTimezone",zone.getId()); data.put("coverageState",coverage(String.valueOf(date)).get("coverageState")); data.put("page",page); data.put("pageSize",pageSize); data.put("total",total==null?0:total); data.put("items",items); return data;
    }

    public Map<String,Object> statistics(String value) {
        LocalDate d=value==null||value.isBlank()?latestAvailableDate():date(value);if(d==null)d=LocalDate.now(zone);
        var cutoff=d.plusDays(1).atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var start=d.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var result=jdbc.queryForMap("SELECT COUNT(*) collected,COALESCE(SUM(p.pool_state='FORMAL'),0) formal,COALESCE(SUM(p.pool_state='PENDING_IDENTIFICATION'),0) pending,COALESCE(SUM(p.pool_state='OBSERVING'),0) observing,COALESCE(SUM(p.first_observed_at>=?),0) firstObserved FROM product p WHERE p.first_observed_at<? AND EXISTS(SELECT 1 FROM platform_version v WHERE v.product_id=p.id AND v.external_id NOT LIKE 'demo-%')",start,cutoff);
        result.put("selections",jdbc.queryForObject("SELECT COUNT(*) FROM selection_record sr WHERE EXISTS(SELECT 1 FROM platform_version v WHERE v.product_id=sr.product_id AND v.external_id NOT LIKE 'demo-%')",Integer.class));
        result.put("date",d);return result;
    }

    public Map<String,Object> product(long id, String value) {
        LocalDate date=value==null||value.isBlank()?latestAvailableDate():date(value); if(date==null) date=LocalDate.now(clock.withZone(zone));
        Map<String,Object> p; try { p=jdbc.queryForMap("SELECT id,name,pool_state,first_observed_at,updated_at,version FROM product WHERE id=?",id); } catch(EmptyResultDataAccessException e){throw new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","产品不存在");}
        Map<String,Object> out=new LinkedHashMap<>(); out.put("id",String.valueOf(p.get("id"))); out.put("name",p.get("name")); out.put("poolState",p.get("pool_state")); out.put("date",date); out.put("firstObservedDate",p.get("first_observed_at")); out.put("thumbnailUrl",IconCache.display(thumbnail(id,date))); out.put("rankings",rankingRows(id,date)); out.put("selections",jdbc.queryForList("SELECT id,reference_version_id,owner_name,status,selected_on,started_on,finished_on,note,version,updated_at FROM selection_record WHERE product_id=? ORDER BY updated_at DESC",id)); out.put("version",p.get("version")); return out;
    }

    public List<Map<String,Object>> rankingRows(long productId, LocalDate date) {
        return jdbc.queryForList("""
          SELECT v.id version_id,v.platform,v.external_id,v.name version_name,c.id chart_id,c.name chart_name,c.region,c.kind,
            s.id snapshot_id,s.completeness,s.covered_top_n,e.rank_position rank_position,
            olde.rank_position old_rank,
            CASE WHEN s.id IS NULL THEN 'MISSING_SNAPSHOT' WHEN e.id IS NULL AND s.completeness='COMPLETE' THEN 'NOT_IN_COVERAGE' WHEN e.id IS NULL THEN 'PARTIAL_COVERAGE' ELSE 'OBSERVED' END data_state,
            CASE WHEN e.rank_position IS NULL OR olde.rank_position IS NULL THEN NULL ELSE olde.rank_position-e.rank_position END delta_7d
          FROM platform_version v JOIN chart_definition c ON c.platform=v.platform AND c.enabled=TRUE JOIN data_source ds ON ds.id=c.source_id AND ds.access_method<>'SIMULATED'
          LEFT JOIN ranking_snapshot s ON s.chart_id=c.id AND s.business_date=? AND s.status='PUBLISHED'
            AND s.revision=(SELECT MAX(s1.revision) FROM ranking_snapshot s1 WHERE s1.chart_id=c.id AND s1.business_date=? AND s1.status='PUBLISHED')
          LEFT JOIN ranking_entry e ON e.snapshot_id=s.id AND e.platform_version_id=v.id
          LEFT JOIN ranking_snapshot olds ON olds.chart_id=c.id AND olds.business_date=DATE_SUB(?,INTERVAL 7 DAY) AND olds.status='PUBLISHED'
            AND olds.revision=(SELECT MAX(s2.revision) FROM ranking_snapshot s2 WHERE s2.chart_id=c.id AND s2.business_date=DATE_SUB(?,INTERVAL 7 DAY) AND s2.status='PUBLISHED')
          LEFT JOIN ranking_entry olde ON olde.snapshot_id=olds.id AND olde.platform_version_id=v.id
          WHERE v.product_id=? ORDER BY v.platform,c.region,c.id""",date,date,date,date,productId).stream().map(r->{
            Map<String,Object> m=new LinkedHashMap<>(); m.put("versionId",String.valueOf(r.get("version_id")));m.put("platform",r.get("platform"));m.put("region",r.get("region"));m.put("versionName",r.get("version_name"));m.put("externalId",r.get("external_id"));m.put("chartId",r.get("chart_id") == null?null:String.valueOf(r.get("chart_id")));m.put("chartName",r.get("chart_name"));m.put("kind",r.get("kind"));m.put("rank",r.get("rank_position"));m.put("delta7d",r.get("delta_7d"));m.put("snapshotId",r.get("snapshot_id") == null?null:String.valueOf(r.get("snapshot_id")));m.put("dataState",r.get("data_state"));m.put("completeness",r.get("completeness"));m.put("monetization",monetization(((Number)r.get("version_id")).longValue(),date));return m;}).toList();
    }

    private String monetization(long versionId, LocalDate asOf) { try { return jdbc.queryForObject("SELECT mode FROM monetization_assessment WHERE platform_version_id=? AND effective_at < DATE_ADD(?, INTERVAL 1 DAY) ORDER BY effective_at DESC,id DESC LIMIT 1",String.class,versionId,asOf); } catch (EmptyResultDataAccessException e){return "UNKNOWN";} }
    private String thumbnail(long productId, LocalDate date) { try { return jdbc.queryForObject("SELECT sm.source_url FROM snapshot_media sm JOIN ranking_snapshot s ON s.id=sm.snapshot_id JOIN platform_version v ON v.id=sm.platform_version_id WHERE v.product_id=? AND s.business_date=? AND s.status='PUBLISHED' ORDER BY s.revision DESC,s.id DESC LIMIT 1",String.class,productId,date); } catch (EmptyResultDataAccessException e){return null;} }
    public List<Map<String,Object>> versions(long productId,String value){ LocalDate d=value==null||value.isBlank()?latestAvailableDate():date(value);if(d==null)d=LocalDate.now(clock.withZone(zone));return rankingRows(productId,d); }
    public List<Map<String,Object>> rankings(long chartId, String value){ LocalDate d=date(value); return jdbc.queryForList("SELECT e.rank_position `rank`,v.id version_id,v.product_id product_id,p.name product_name,v.name version_name,s.id snapshot_id,s.completeness,sm.source_url thumbnail_url,e.rating,e.source_metrics FROM ranking_snapshot s JOIN ranking_entry e ON e.snapshot_id=s.id JOIN platform_version v ON v.id=e.platform_version_id JOIN product p ON p.id=v.product_id LEFT JOIN snapshot_media sm ON sm.snapshot_id=s.id AND sm.platform_version_id=v.id WHERE s.chart_id=? AND s.business_date=? AND s.status='PUBLISHED' AND s.revision=(SELECT MAX(s2.revision) FROM ranking_snapshot s2 WHERE s2.chart_id=? AND s2.business_date=? AND s2.status='PUBLISHED') ORDER BY e.rank_position",chartId,d,chartId,d).stream().peek(r->r.put("thumbnail_url",IconCache.display(r.get("thumbnail_url")))).toList(); }
}
