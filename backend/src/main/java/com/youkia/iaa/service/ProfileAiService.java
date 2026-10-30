package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youkia.iaa.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Statement;
import java.time.*;
import java.util.*;

@Service
public class ProfileAiService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final GameProfileService profiles;private final ProfileAiClient client;private final AiConfigService config;private final TransactionTemplate tx;
    public ProfileAiService(JdbcTemplate jdbc,ObjectMapper mapper,GameProfileService profiles,ProfileAiClient client,AiConfigService config,PlatformTransactionManager manager){this.jdbc=jdbc;this.mapper=mapper;this.profiles=profiles;this.client=client;this.config=config;this.tx=new TransactionTemplate(manager);}
    public Map<String,Object> extract(JsonNode request){
        long listing=request.path("listingId").asLong(),evidenceId=request.path("evidenceId").asLong();profiles.listing(listing);
        String actor=request.path("actor").asText("");if(actor.isBlank()||actor.length()>100)throw new IllegalArgumentException("填写AI提取发起人");
        if(!request.path("modelTransferConfirmed").asBoolean())throw new IllegalArgumentException("先确认所选摘录可发送到已配置的模型服务");
        var evidence=profiles.evidence(evidenceId,LocalDate.now(ZoneId.of("Asia/Shanghai")),"Asia/Shanghai");if(((Number)evidence.get("listing_id")).longValue()!=listing)throw new ApiException(HttpStatus.FORBIDDEN,"EVIDENCE_SCOPE_MISMATCH","证据不属于目标平台市场");
        if(!request.path("fieldKeys").isArray()||request.path("fieldKeys").isEmpty()||request.path("fieldKeys").size()>10)throw new IllegalArgumentException("每次选择1～10项字段");
        var catalog=new ProfileFieldCatalog(mapper);var keys=new LinkedHashSet<String>();var definitions=new ArrayList<JsonNode>();for(var key:request.path("fieldKeys")){if(!key.isTextual()||!keys.add(key.asText()))throw new IllegalArgumentException("字段键无效或重复");definitions.add(catalog.field(key.asText()));}
        String version=request.path("appVersion").asText("");if(keys.stream().anyMatch(catalog::sensitive)&&jdbc.queryForObject("SELECT COUNT(*) FROM release_record WHERE tenant_id=? AND listing_id=? AND app_version=?",Integer.class,profiles.tenant(),listing,version)!=1)throw new IllegalArgumentException("所选字段与应用版本有关，请先复核登记目标版本");
        AiConfigService.RuntimeConfig configured;String configurationError=null;try{configured=config.runtime();}catch(IllegalStateException error){var safe=config.get();configured=new AiConfigService.RuntimeConfig(String.valueOf(safe.get("baseUrl")),String.valueOf(safe.get("model")),"",60,0.2,false);configurationError="AI_CONFIG_DECRYPTION_FAILED";}final var provider=configured;final String configError=configurationError;var input=mapper.createObjectNode();input.put("listingId",listing);input.put("appVersion",version);input.set("fields",mapper.valueToTree(definitions));input.set("evidence",mapper.valueToTree(Map.of("evidenceId",evidenceId,"excerpt",evidence.get("excerpt"),"locator",evidence.get("locator"),"observedAt",evidence.get("observed_at").toString(),"sourceName",evidence.get("source_name"))));
        String hash=MarketWeeklySkill.hash(json(Map.of("input",input,"model",provider.model(),"baseUrl",provider.baseUrl(),"temperature",provider.temperature(),"prompt",ProfileAiClient.PROMPT,"schema",ProfileAiClient.SCHEMA)));
        var previous=jdbc.queryForList("SELECT id,status,result_json FROM profile_ai_run WHERE tenant_id=? AND listing_id=? AND input_sha256=? AND status IN ('SUCCEEDED','ABSTAINED') ORDER BY id DESC LIMIT 1",profiles.tenant(),listing,hash);if(!previous.isEmpty()){var old=previous.getFirst();return Map.of("id",old.get("id"),"status",old.get("status"),"reused",true,"result",read(old.get("result_json")));}
        var holder=new GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement("INSERT INTO profile_ai_run(tenant_id,listing_id,evidence_id,field_keys,model,prompt_version,schema_version,input_sha256,status,actor,created_at) VALUES(?,?,?,?,?,?,?,?,'RUNNING',?,UTC_TIMESTAMP(3))",Statement.RETURN_GENERATED_KEYS);Object[] args={profiles.tenant(),listing,evidenceId,json(keys),provider.model(),ProfileAiClient.PROMPT,ProfileAiClient.SCHEMA,hash,actor};for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},holder);long run=Objects.requireNonNull(holder.getKey()).longValue();
        try{
            if(configError!=null)throw new ProfileAiClient.Failure(configError);var output=client.extract(provider,input);validateOutput(output,keys,evidenceId,String.valueOf(evidence.get("excerpt")),catalog);
            return tx.execute(status->{var submissions=new ArrayList<Object>();for(var claim:output.path("claims")){
                ObjectNode b=mapper.createObjectNode();b.put("listingId",listing);b.put("fieldKey",claim.path("fieldKey").asText());b.put("kind","inference");b.put("status","available");b.set("value",claim.get("value"));b.put("submitter",actor);b.put("observedAt",GameProfileResolver.time(evidence.get("observed_at")).atOffset(ZoneOffset.UTC).toString());b.put("sourceName",String.valueOf(evidence.get("source_name")));b.put("originFamily",String.valueOf(evidence.get("origin_family")));b.put("sourceUrl",(String)evidence.get("source_url"));b.put("excerpt",claim.path("quote").asText());b.put("locator",evidence.get("locator")+" / "+claim.path("locator").asText());b.put("rights",String.valueOf(evidence.get("rights")));b.put("appVersion",version);b.put("isSynthetic",Boolean.TRUE.equals(evidence.get("is_synthetic"))||evidence.get("is_synthetic") instanceof Number n&&n.intValue()!=0);b.set("modelMetadata",mapper.valueToTree(Map.of("model",provider.model(),"promptVersion",ProfileAiClient.PROMPT,"schemaVersion",ProfileAiClient.SCHEMA,"inputHash",hash,"runId",run,"evidenceId",evidenceId)));
                submissions.add(profiles.submit(b,"ai:"+hash+":"+claim.path("fieldKey").asText()).get("id"));
            }
                String resultStatus=submissions.isEmpty()?"ABSTAINED":"SUCCEEDED";var result=Map.of("submissionIds",submissions,"unknownFields",output.path("unknownFields"),"abstentionReason",output.path("abstentionReason").asText(),"notice","仅生成待复核材料，批准后仍为AI草案，不能证明纯IAA资格");finish(run,resultStatus,MarketWeeklySkill.hash(json(output)),result);
                if(!output.path("unknownFields").isEmpty())profiles.plan(listing,LocalDate.now(ZoneId.of("Asia/Shanghai")),"Asia/Shanghai");return Map.of("id",run,"status",resultStatus,"reused",false,"result",result);
            });
        }catch(RuntimeException e){String code=e instanceof ProfileAiClient.Failure failure?failure.code:"AI_BUSINESS_VALIDATION_FAILED";var result=Map.of("errorCode",code,"retryCondition","核对模型配置、材料权限、字段类型或网络后重新发起；输入证据和字段已保存","notice","提取失败，没有修改档案事实或资格");finish(run,"FAILED",null,result);profiles.plan(listing,LocalDate.now(ZoneId.of("Asia/Shanghai")),"Asia/Shanghai");return Map.of("id",run,"status","FAILED","reused",false,"result",result);}
    }
    public static void validateOutput(JsonNode output,Set<String> keys,long evidenceId,String excerpt,ProfileFieldCatalog catalog){
        var accounted=new HashSet<String>();for(var c:output.path("claims")){String key=c.path("fieldKey").asText();if(!keys.contains(key)||!accounted.add(key)||c.path("evidenceId").asLong()!=evidenceId||c.path("quote").asText().isBlank()||!excerpt.contains(c.path("quote").asText()))throw new ProfileAiClient.Failure("AI_REFERENCE_OR_SCOPE_INVALID");catalog.validate(key,c.get("value"),"available");}
        for(var k:output.path("unknownFields"))if(!keys.contains(k.asText())||!accounted.add(k.asText()))throw new ProfileAiClient.Failure("AI_UNKNOWN_FIELDS_INVALID");if(!accounted.equals(keys))throw new ProfileAiClient.Failure("AI_FIELD_COVERAGE_INVALID");if(output.path("claims").isEmpty()&&output.path("abstentionReason").asText().isBlank())throw new ProfileAiClient.Failure("AI_ABSTENTION_REASON_REQUIRED");
    }
    private void finish(long id,String status,String hash,Object result){jdbc.update("UPDATE profile_ai_run SET status=?,output_sha256=?,result_json=?,finished_at=UTC_TIMESTAMP(3) WHERE id=? AND tenant_id=?",status,hash,json(result),id,profiles.tenant());}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException("AI任务JSON无效");}}
    private JsonNode read(Object v){try{return mapper.readTree(String.valueOf(v));}catch(Exception e){throw new IllegalStateException("AI运行记录损坏");}}
}
