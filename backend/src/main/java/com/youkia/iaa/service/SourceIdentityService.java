package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Human review is explicit, tenant scoped, revision checked and append only. */
@Service
public class SourceIdentityService {
    public static final Set<String> FAMILIES=Set.of("消除","合成","塔防","跑酷","解谜","益智","模拟经营","其他/重度");
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final String tenant;
    public SourceIdentityService(JdbcTemplate j,ObjectMapper m,@org.springframework.beans.factory.annotation.Value("${PROFILE_TENANT_ID:default}")String t){jdbc=j;mapper=m;tenant=t;}
    public Map<String,Map<String,Object>> states(LocalDate day,LocalDateTime cutoff){
        var rows=jdbc.queryForList("""
            SELECT e.*,p.name product_name FROM source_entity_review_event e LEFT JOIN product p ON p.id=e.product_id
            WHERE e.tenant_id=? AND e.effective_date<=? AND e.recorded_at<=? ORDER BY e.id DESC
            """,tenant,day,cutoff);
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(var r:rows){var evidence=read(r.get("evidence"));r.put("evidence",evidence);if(evidence.hasNonNull("identityProductName"))r.put("product_name",evidence.path("identityProductName").asText());result.putIfAbsent(r.get("entity_sha256").toString(),r);}
        return result;
    }
    public void enrich(List<Map<String,Object>> rows,LocalDate day,LocalDateTime cutoff){
        var states=states(day,cutoff);
        // Only official Apple numeric identifiers can reuse already-established platform identities.
        var apple=jdbc.queryForList("""
            SELECT v.external_id,v.product_id,p.name FROM platform_version v JOIN product p ON p.id=v.product_id
            WHERE v.platform='IOS' AND v.created_at<=? AND p.created_at<=? AND v.updated_at<=? AND p.updated_at<=?
              AND EXISTS(SELECT 1 FROM market_listing l WHERE l.platform_version_id=v.id AND l.tenant_id=? AND l.available_at<=?)
            """,cutoff,cutoff,cutoff,cutoff,tenant,cutoff);
        Map<String,Map<String,Object>> ids=new HashMap<>();apple.forEach(r->ids.put(r.get("external_id").toString(),r));
        for(var row:rows){var state=states.get(SourceEntity.sha(row));
            if(state!=null){row.put("identity_review",state);
                if(state.get("product_id")!=null){row.put("unified_product_id",state.get("product_id"));row.put("unified_product_name",state.get("product_name"));row.put("association_basis","HUMAN_REVIEW");}
                if(state.get("gameplay_family")!=null)row.put("reviewed_gameplay",Map.of("family",state.get("gameplay_family"),"basis","HUMAN_REVIEW","quote",((JsonNode)state.get("evidence")).path("gameplayEvidence").path("excerpt").asText(),"evidence",((JsonNode)state.get("evidence")).path("gameplayEvidence"),"verified",true,"label","人工核验的本平台玩法","eventId",state.get("id")));
            }
            boolean reviewed=state!=null&&(Boolean.TRUE.equals(state.get("identity_reviewed"))||state.get("identity_reviewed") instanceof Number n&&n.intValue()!=0);
            var id=ids.get(SourceEntity.text(row,"external_id"));
            if(!reviewed&&"apple-games".equals(row.get("source_key"))&&"IOS".equals(row.get("platform"))&&id!=null){row.put("unified_product_id",id.get("product_id"));row.put("unified_product_name",id.get("name"));row.put("association_basis","EXISTING_OFFICIAL_PLATFORM_ID");}
        }
    }
    public List<Map<String,Object>> products(String q){return jdbc.queryForList("""
        SELECT p.id,p.name FROM product p WHERE p.name LIKE ? AND (
          EXISTS(SELECT 1 FROM platform_version v JOIN market_listing l ON l.platform_version_id=v.id WHERE v.product_id=p.id AND l.tenant_id=?)
          OR EXISTS(SELECT 1 FROM source_entity_review_event e WHERE e.product_id=p.id AND e.tenant_id=?))
        ORDER BY p.name,p.id LIMIT 50
        ""","%"+Objects.toString(q,"")+"%",tenant,tenant);}
    public List<Map<String,Object>> history(long observation,int rank){var row=source(observation,rank);var list=jdbc.queryForList("SELECT * FROM source_entity_review_event WHERE tenant_id=? AND entity_sha256=? ORDER BY revision DESC",tenant,SourceEntity.sha(row));list.forEach(r->r.put("evidence",read(r.get("evidence"))));return list;}
    private Map<String,Object> source(long observation,int rank){
        var rows=jdbc.queryForList("""
            SELECT o.id observation_id,o.source_key,o.platform,o.region,o.business_date,e.external_id,e.name,e.developer,e.rank_position
            FROM external_ranking_observation o JOIN external_ranking_entry e ON e.observation_id=o.id
            WHERE o.tenant_id=? AND o.id=? AND e.rank_position=?
              AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.tenant_id=o.tenant_id AND r.observation_id=o.id AND r.status IN ('SUCCEEDED','PARTIAL'))
            """,tenant,observation,rank);
        if(rows.isEmpty())throw bad("来源观察不存在或不属于当前工作区");return rows.getFirst();
    }
    @Transactional public Map<String,Object> review(JsonNode body,String requestKey){
        if(requestKey==null||!requestKey.matches("[a-zA-Z0-9-]{16,64}"))throw bad("缺少有效幂等键");
        String hash=MarketWeeklySkill.hash(body.toString());
        var old=jdbc.queryForList("SELECT id,product_id,revision,request_sha256 FROM source_entity_review_event WHERE tenant_id=? AND request_key=?",tenant,requestKey);
        if(!old.isEmpty()){if(old.stream().anyMatch(r->!hash.equals(r.get("request_sha256"))))throw conflict("同一请求键不能用于不同内容");return Map.of("items",old,"reused",true);}
        String action=body.path("action").asText(),actor=body.path("actor").asText("").trim(),url=body.path("evidenceUrl").asText("").trim(),quote=body.path("excerpt").asText("").trim(),reason=body.path("reason").asText("").trim();
        if(!Set.of("LINK","UNLINK","CLASSIFY","CLEAR_GAMEPLAY").contains(action)||actor.isEmpty()||actor.length()>100||!url.matches("https?://[^\\s]+")||url.length()>2000||quote.length()<10||quote.length()>4000||reason.length()<5||reason.length()>2000)throw bad("请填写核对人、有效证据网址、至少10字摘录和至少5字判断理由");
        LocalDate date;try{date=LocalDate.parse(body.path("effectiveDate").asText());}catch(Exception e){throw bad("需要生效业务日");}
        if(date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw bad("不接受未来生效结论");
        if(!body.path("members").isArray()||body.path("members").isEmpty()||body.path("members").size()>20)throw bad("一次核验1至20个来源身份");
        Map<String,Map<String,Object>> members=new TreeMap<>();
        for(var m:body.path("members")){var row=source(m.path("observationId").asLong(),m.path("rank").asInt());
            if(date.isBefore(LocalDate.parse(row.get("business_date").toString())))throw bad("生效日不能早于所引来源观察");
            if(SourceEntity.text(row,"developer").isBlank()&&SourceEntity.text(row,"external_id").isBlank())throw bad("缺少发行方与稳定ID的条目不能建立跨日身份或玩法核验");
            row.put("expectedRevision",m.path("revision").asLong(-1));if(members.put(SourceEntity.sha(row),row)!=null)throw bad("同一来源身份不能重复提交");
        }
        Map<String,Map<String,Object>> prior=new HashMap<>();
        Map<String,Long> revisions=new HashMap<>();
        for(var e:members.entrySet()){
            jdbc.update("INSERT IGNORE INTO source_entity_review_head(tenant_id,entity_sha256,revision) VALUES(?,?,0)",tenant,e.getKey());
            long rev=jdbc.queryForObject("SELECT revision FROM source_entity_review_head WHERE tenant_id=? AND entity_sha256=? FOR UPDATE",Long.class,tenant,e.getKey());
            revisions.put(e.getKey(),rev);
            var previous=jdbc.queryForList("SELECT * FROM source_entity_review_event WHERE tenant_id=? AND entity_sha256=? ORDER BY revision DESC LIMIT 1 FOR UPDATE",tenant,e.getKey());
            if(!previous.isEmpty()){var p=previous.getFirst();if(date.isBefore(LocalDate.parse(p.get("effective_date").toString())))throw bad("新修订生效日不能早于上一修订；历史修正需按当前业务日登记");prior.put(e.getKey(),p);}
        }
        var retry=jdbc.queryForList("SELECT id,product_id,revision,request_sha256 FROM source_entity_review_event WHERE tenant_id=? AND request_key=? FOR UPDATE",tenant,requestKey);
        if(!retry.isEmpty()){if(retry.stream().anyMatch(r->!hash.equals(r.get("request_sha256"))))throw conflict("同一请求键不能用于不同内容");return Map.of("items",retry,"reused",true);}
        for(var e:members.entrySet())if(!revisions.get(e.getKey()).equals(e.getValue().get("expectedRevision")))throw conflict("核验记录已更新，请刷新后重试");
        Long product=null;
        if(action.equals("LINK")){
            if(!body.path("confirmedSameProduct").asBoolean())throw bad("请明确确认已核对为同一产品，不能仅凭同名");
            if(body.path("productId").asLong()>0){product=body.path("productId").asLong();final long id=product;
                if(productsById(id).isEmpty())throw bad("统一产品不存在或不属于当前工作区");
            }else{
                String name=body.path("productName").asText("").trim();if(name.isEmpty()||name.length()>200)throw bad("请填写统一产品名称");
                jdbc.update("INSERT INTO product(name,pool_state,first_observed_at,created_at,updated_at) VALUES(?,'PENDING_IDENTIFICATION',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",name);
                product=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            }
        }
        String family=body.path("family").asText();if(action.equals("CLASSIFY")&&!FAMILIES.contains(family))throw bad("请选择支持的玩法分类；其他或重度不会自动进入轻度推荐");
        List<Map<String,Object>> result=new ArrayList<>();
        for(var e:members.entrySet()){
            var p=prior.getOrDefault(e.getKey(),Map.of());var r=e.getValue();long revision=(long)r.get("expectedRevision")+1;
            Object pid=action.equals("LINK")?product:action.equals("UNLINK")?null:p.get("product_id");
            boolean reviewed=Set.of("LINK","UNLINK").contains(action)||Boolean.TRUE.equals(p.get("identity_reviewed"))||p.get("identity_reviewed") instanceof Number n&&n.intValue()!=0;
            Object f=action.equals("CLASSIFY")?family:action.equals("CLEAR_GAMEPLAY")?null:p.get("gameplay_family");
            var provenance=mapper.createObjectNode();
            if(p.get("evidence")!=null)provenance.setAll((com.fasterxml.jackson.databind.node.ObjectNode)read(p.get("evidence")));
            var proof=mapper.valueToTree(Map.of("url",url,"excerpt",quote,"reason",reason,"actor",actor,"effectiveDate",date.toString()));
            provenance.set("operationEvidence",proof);
            if(Set.of("LINK","UNLINK").contains(action))provenance.set("identityEvidence",proof);
            if(action.equals("LINK"))provenance.put("identityProductName",jdbc.queryForObject("SELECT name FROM product WHERE id=?",String.class,pid));
            if(Set.of("CLASSIFY","CLEAR_GAMEPLAY").contains(action))provenance.set("gameplayEvidence",proof);
            jdbc.update("""
                INSERT INTO source_entity_review_event(tenant_id,entity_sha256,revision,request_key,request_sha256,observation_id,rank_position,action,product_id,identity_reviewed,gameplay_family,effective_date,evidence,actor,recorded_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """,tenant,e.getKey(),revision,requestKey,hash,r.get("observation_id"),r.get("rank_position"),action,pid,reviewed,f,date,json(provenance),actor);
            jdbc.update("UPDATE source_entity_review_head SET revision=? WHERE tenant_id=? AND entity_sha256=?",revision,tenant,e.getKey());
            result.add(Map.of("entitySha256",e.getKey(),"revision",revision));
        }
        var out=new LinkedHashMap<String,Object>();out.put("items",result);out.put("productId",product);out.put("reused",false);return out;
    }
    private List<Map<String,Object>> productsById(long id){return jdbc.queryForList("SELECT p.id FROM product p WHERE p.id=? AND (EXISTS(SELECT 1 FROM platform_version v JOIN market_listing l ON l.platform_version_id=v.id WHERE v.product_id=p.id AND l.tenant_id=?) OR EXISTS(SELECT 1 FROM source_entity_review_event e WHERE e.product_id=p.id AND e.tenant_id=?))",id,tenant,tenant);}
    private JsonNode read(Object v){try{return v instanceof byte[] b?mapper.readTree(b):mapper.readTree(v.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private static ApiException bad(String s){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"IDENTITY_REVIEW_INVALID",s);}
    private static ApiException conflict(String s){return new ApiException(HttpStatus.CONFLICT,"IDENTITY_REVIEW_CONFLICT",s);}
}
