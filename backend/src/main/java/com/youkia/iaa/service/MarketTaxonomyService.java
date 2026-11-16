package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youkia.iaa.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Evidence-backed market taxonomy. A term is not a product identity or a recommendation. */
@Service
public class MarketTaxonomyService {
    private static final Set<String> TYPES=Set.of("GENRE","THEME","MECHANIC");
    private static final Set<String> SCOPES=Set.of("PRODUCT","VERSION","MARKET");
    private final JdbcTemplate jdbc; private final String tenant;
    public MarketTaxonomyService(JdbcTemplate jdbc,@Value("${PROFILE_TENANT_ID:default}") String tenant){this.jdbc=jdbc;this.tenant=tenant;}

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(LocalDate asOf){
        LocalDate day=asOf==null?LocalDate.now(ZoneId.of("Asia/Shanghai")):asOf;
        var rows=jdbc.queryForList("""
            SELECT t.id,t.term_type,t.canonical_name,t.definition,t.status,t.revision,t.created_by,t.created_at,
              t.parent_id,COALESCE(COUNT(o.id),0) observation_count,
              COALESCE(SUM(o.status='CONFIRMED'),0) confirmed_count,
              MAX(o.observed_date) latest_observed_date
            FROM market_taxonomy_term t LEFT JOIN market_taxonomy_observation o
              ON o.term_id=t.id AND o.tenant_id=? AND o.observed_date<=?
            WHERE t.tenant_id=? GROUP BY t.id ORDER BY t.term_type,t.canonical_name,t.id
            """,tenant,day,tenant);
        return rows;
    }

    @Transactional
    public Map<String,Object> createTerm(JsonNode body){
        String type=required(body,"termType").toUpperCase(Locale.ROOT),name=required(body,"canonicalName"),actor=required(body,"actor");
        if(!TYPES.contains(type))bad("分类类型必须是GENRE、THEME或MECHANIC");
        if(name.length()>160||actor.length()>100)bad("名称最多160字，登记人最多100字");
        String definition=required(body,"definition");if(definition.length()>2000)bad("分类定义超过2000字");
        long parent=body.path("parentId").asLong(0);if(parent>0&&jdbc.queryForObject("SELECT COUNT(*) FROM market_taxonomy_term WHERE tenant_id=? AND id=? AND term_type=?",Integer.class,tenant,parent,type)!=1)bad("父级词条不存在");
        String normalized=normalize(name);
        var old=jdbc.queryForList("SELECT id FROM market_taxonomy_term WHERE tenant_id=? AND term_type=? AND normalized_name=?",tenant,type,normalized);
        if(!old.isEmpty())return Map.of("id",old.getFirst().get("id"),"reused",true);
        jdbc.update("INSERT INTO market_taxonomy_term(tenant_id,term_type,canonical_name,normalized_name,parent_id,definition,created_by,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE id=id",tenant,type,name,normalized,parent>0?parent:null,definition,actor);
        long id=jdbc.queryForObject("SELECT id FROM market_taxonomy_term WHERE tenant_id=? AND term_type=? AND normalized_name=?",Long.class,tenant,type,normalized);
        return Map.of("id",id,"reused",false,"status","ACTIVE");
    }

    @Transactional
    public Map<String,Object> observe(long termId,JsonNode body){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM market_taxonomy_term WHERE tenant_id=? AND id=?",Integer.class,tenant,termId)!=1)bad("分类词条不存在");
        String actor=required(body,"actor"),source=required(body,"sourceName"),excerpt=required(body,"excerpt");
        String scope=required(body,"scope").toUpperCase(Locale.ROOT);if(!SCOPES.contains(scope))bad("适用范围必须是PRODUCT、VERSION或MARKET");
        String market=text(body,"market","").toUpperCase(Locale.ROOT);if(!market.isBlank()&&!Set.of("CN","US","GB","DE","FR").contains(market))bad("市场代码无效");
        if(actor.length()>100||source.length()>200)bad("登记人最多100字，来源名称最多200字");
        String date=required(body,"observedDate");try{if(LocalDate.parse(date).isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))bad("观察日期不能晚于今天");}catch(Exception e){bad("观察日期无效");}
        long product=body.path("productId").asLong(0),version=body.path("platformVersionId").asLong(0);
        if(product<0||version<0)bad("产品和版本ID必须为正整数");
        if(product>0&&jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=?",Integer.class,product)!=1)bad("统一产品不存在");
        if(version>0&&jdbc.queryForObject("SELECT COUNT(*) FROM platform_version WHERE id=?",Integer.class,version)!=1)bad("平台版本不存在");
        if(scope.equals("PRODUCT")&&product==0)bad("产品级分类必须填写统一产品ID");
        if(version>0&&product>0&&jdbc.queryForObject("SELECT COUNT(*) FROM platform_version WHERE id=? AND product_id=?",Integer.class,version,product)!=1)bad("平台版本不属于该产品");
        if(scope.equals("PRODUCT")&&version>0)bad("版本级证据请选择VERSION范围");
        if(scope.equals("VERSION")&&version==0)bad("版本级分类必须填写平台版本ID");
        if(scope.equals("MARKET")&&market.isBlank())bad("市场级分类必须填写市场代码");
        String url=text(body,"sourceUrl","");if(url.length()>2000)bad("来源链接过长");if(!url.isBlank()&&!url.matches("https?://[^\\s]+"))bad("来源链接必须是http或https");
        String hash=sha(List.of(termId,product,version,market,scope,source,url,excerpt,date).toString());
        var old=jdbc.queryForList("SELECT id,status FROM market_taxonomy_observation WHERE tenant_id=? AND content_sha256=?",tenant,hash);
        if(!old.isEmpty())return Map.of("id",old.getFirst().get("id"),"reused",true,"status",old.getFirst().get("status"));
        jdbc.update("INSERT INTO market_taxonomy_observation(tenant_id,term_id,product_id,platform_version_id,market,scope,source_name,source_url,excerpt,observed_date,available_at,content_sha256,status,actor,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?,'PENDING_REVIEW',?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE id=id",tenant,termId,product>0?product:null,version>0?version:null,market.isBlank()?null:market,scope,source,url.isBlank()?null:url,excerpt,date,hash,actor);
        long id=jdbc.queryForObject("SELECT id FROM market_taxonomy_observation WHERE tenant_id=? AND content_sha256=?",Long.class,tenant,hash);
        return Map.of("id",id,"reused",false,"status","PENDING_REVIEW");
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> observations(long termId,LocalDate day){
        return jdbc.queryForList("""
            SELECT o.*,p.name product_name,v.name version_name,v.platform
            FROM market_taxonomy_observation o
            LEFT JOIN product p ON p.id=o.product_id LEFT JOIN platform_version v ON v.id=o.platform_version_id
            WHERE o.tenant_id=? AND o.term_id=? AND o.observed_date<=?
            ORDER BY o.observed_date DESC,o.id DESC LIMIT 200
            """,tenant,termId,day==null?LocalDate.now(ZoneId.of("Asia/Shanghai")):day);
    }

    /** Freeze matching evidence only; pending classification is never a novelty or IAA conclusion. */
    public void attachEvidence(List<Map<String,Object>> leads,LocalDate day,java.time.LocalDateTime cutoff){
        var evidence=jdbc.queryForList("""
            SELECT o.id,o.product_id,o.platform_version_id,o.market,o.scope,o.source_name,o.source_url,
              o.excerpt,o.observed_date,o.available_at,o.content_sha256,o.status,
              t.term_type,t.canonical_name,t.definition,v.platform,v.external_id
            FROM market_taxonomy_observation o JOIN market_taxonomy_term t ON t.id=o.term_id AND t.tenant_id=o.tenant_id
            LEFT JOIN platform_version v ON v.id=o.platform_version_id
            WHERE o.tenant_id=? AND o.observed_date<=? AND o.available_at<=? AND o.status<>'REJECTED'
            ORDER BY o.id
            """,tenant,day,cutoff);
        for(var lead:leads){
            var matches=evidence.stream().filter(e->e.get("market")==null||Objects.toString(e.get("market")).equalsIgnoreCase(Objects.toString(lead.get("region"))))
                .filter(e->"PRODUCT".equals(e.get("scope"))?e.get("product_id")!=null&&Objects.toString(e.get("product_id")).equals(Objects.toString(lead.get("unifiedProductId"))):
                    "VERSION".equals(e.get("scope"))&&e.get("external_id")!=null&&Objects.toString(e.get("external_id")).equals(Objects.toString(lead.get("externalId")))&&Objects.equals(e.get("platform"),lead.get("platform")))
                .toList();
            lead.put("taxonomyEvidence",matches);
            lead.put("noveltyStatus","UNVERIFIED");
        }
    }

    private String required(JsonNode n,String key){String v=n.path(key).asText("").trim();if(v.isBlank()||v.length()>2000)bad("必填字段无效："+key);return v;}
    private String text(JsonNode n,String key,String fallback){return n.path(key).asText(fallback).trim();}
    private String normalize(String v){return v.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
    private String sha(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void bad(String m){throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_MARKET_TAXONOMY",m);}
}
