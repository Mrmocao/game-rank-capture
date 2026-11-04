package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.*;
import com.youkia.capture.web.*;
import com.youkia.iaa.service.SecretCipher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;

/** Online configuration and execution. Writes only capture_*; generic records never become ranking facts. */
public final class OnlineWebCaptureService {
    private final CaptureWorkspaceStore store;private final WebTransport transport;private final SecretCipher cipher;
    private final Path root;private final TransactionTemplate tx;private final String trustedBase;private boolean encryptionReady=true;
    public OnlineWebCaptureService(CaptureWorkspaceStore store,WebTransport transport,SecretCipher cipher,Path root,String trustedBase) {
        this.store=store;this.transport=transport;this.cipher=cipher;this.root=root.toAbsolutePath().normalize();this.trustedBase=trustedBase;
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(store.jdbc.getDataSource())));
    }
    public OnlineWebCaptureService requireEncryptionKey(boolean ready){this.encryptionReady=ready;return this;}
    public record ProviderEdit(String baseUrl,String apiKey,boolean clearKey,boolean enabled,int revision){@Override public String toString(){return "ProviderEdit[redacted]";}}
    public Map<String,Object> provider(String tenant) {
        var rows=store.jdbc.queryForList("SELECT base_url,api_key_ciphertext,enabled,revision FROM capture_web_provider WHERE tenant_id=?",tenant);
        if(rows.isEmpty())return Map.of("baseUrl",trustedBase.isBlank()?"https://api.firecrawl.dev/v2":trustedBase,"apiKeyConfigured",false,"enabled",false,"revision",0,"selfHosted",!trustedBase.isBlank());
        var r=rows.getFirst();r.put("apiKeyConfigured",r.remove("api_key_ciphertext")!=null);r.put("baseUrl",r.remove("base_url"));r.put("selfHosted",Objects.equals(r.get("baseUrl"),trustedBase));return r;
    }
    public Map<String,Object> saveProvider(String tenant,ProviderEdit edit) {
        String base=transport.providerUrl(edit.baseUrl());
        if(edit.apiKey()!=null&&(edit.apiKey().length()>4096||edit.apiKey().contains("\n")||edit.apiKey().contains("\r")))throw new IllegalArgumentException("API Key格式无效");
        tx.executeWithoutResult(s->{
            store.jdbc.update("INSERT IGNORE INTO capture_web_provider(tenant_id,base_url,enabled,revision,updated_at) VALUES(?,?,FALSE,0,?)",tenant,base,store.now());
            var old=store.jdbc.queryForMap("SELECT * FROM capture_web_provider WHERE tenant_id=? FOR UPDATE",tenant);
            if(((Number)old.get("revision")).intValue()!=edit.revision())throw conflict();
            Object encrypted=old.get("api_key_ciphertext");
            boolean supplied=edit.apiKey()!=null&&!edit.apiKey().isBlank();
            if(supplied&&!encryptionReady)throw new CaptureException("ENCRYPTION_KEY_REQUIRED","服务器须先配置CAPTURE_TOOL_ENCRYPTION_KEY，再保存供应商密钥");
            if(!Objects.equals(old.get("base_url"),base)&&encrypted!=null&&!supplied&&!edit.clearKey())throw new CaptureException("KEY_REENTRY_REQUIRED","更换服务地址时需重新填写Key或明确清空，防止旧凭据发送到新服务");
            if(edit.clearKey())encrypted=null;if(supplied)encrypted=cipher.encrypt(edit.apiKey().strip());
            if(edit.enabled()&&base.equals("https://api.firecrawl.dev/v2")&&encrypted==null)throw new CaptureException("FIRECRAWL_KEY_REQUIRED","官方云服务需要API Key；自建受信服务可不填");
            store.jdbc.update("UPDATE capture_web_provider SET base_url=?,api_key_ciphertext=?,enabled=?,revision=revision+1,updated_at=? WHERE tenant_id=?",base,encrypted,edit.enabled(),store.now(),tenant);
        });return provider(tenant);
    }
    WebTransport.Provider runtime(String tenant,String tool) {
        if(tool.equals("web-http"))return new WebTransport.Provider("","",true,0);
        var rows=store.jdbc.queryForList("SELECT * FROM capture_web_provider WHERE tenant_id=?",tenant);
        if(rows.isEmpty())return new WebTransport.Provider("","",false,0);
        var r=rows.getFirst();return new WebTransport.Provider((String)r.get("base_url"),r.get("api_key_ciphertext")==null?"":cipher.decrypt((String)r.get("api_key_ciphertext")),Boolean.TRUE.equals(r.get("enabled")),((Number)r.get("revision")).intValue());
    }
    public static boolean dynamic(Map<String,Object> target){return target.get("source_key")==null&&Set.of("firecrawl","web-http").contains(target.get("tool_key"));}
    private Map<String,Object> target(String tenant,String key){var t=store.target(tenant,key);if(!dynamic(t))throw new CaptureException("ONLINE_RULE_UNSUPPORTED","仅Firecrawl或普通网页目标支持在线规则，专用榜单保持其独立适配器");return t;}
    public record RuleEdit(int revision,WebCaptureRule rule){}
    public Map<String,Object> rules(String tenant,String key) {
        target(tenant,key);var pointers=store.jdbc.queryForList("SELECT draft_version,published_version,revision,updated_at FROM capture_web_rule WHERE tenant_id=? AND target_key=?",tenant,key);
        var out=new LinkedHashMap<String,Object>();out.put("state",pointers.isEmpty()?Map.of("draft_version",0,"revision",0):pointers.getFirst());
        var versions=store.jdbc.queryForList("SELECT version,tool_key,rule_json,created_at FROM capture_web_rule_version WHERE tenant_id=? AND target_key=? ORDER BY version DESC LIMIT 100",tenant,key);
        versions.forEach(v->v.put("rule_json",store.read(v.get("rule_json"))));out.put("versions",versions);return out;
    }
    public Map<String,Object> saveRule(String tenant,String key,RuleEdit edit) {
        var t=target(tenant,key);if(edit.rule()==null)throw new IllegalArgumentException("规则不能为空");PublicWebUrl.check(edit.rule().url());
        tx.executeWithoutResult(s->{
            store.jdbc.update("INSERT IGNORE INTO capture_web_rule(tenant_id,target_key,draft_version,revision,updated_at) VALUES(?,?,0,0,?)",tenant,key,store.now());
            var pointer=store.jdbc.queryForMap("SELECT * FROM capture_web_rule WHERE tenant_id=? AND target_key=? FOR UPDATE",tenant,key);
            if(((Number)pointer.get("revision")).intValue()!=edit.revision())throw conflict();
            int version=((Number)pointer.get("draft_version")).intValue()+1;
            store.jdbc.update("INSERT INTO capture_web_rule_version(tenant_id,target_key,version,tool_key,rule_json,created_at) VALUES(?,?,?,?,?,?)",tenant,key,version,t.get("tool_key"),store.json(edit.rule()),store.now());
            store.jdbc.update("UPDATE capture_web_rule SET draft_version=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND target_key=?",version,store.now(),tenant,key);
        });return rules(tenant,key);
    }
    public Map<String,Object> publish(String tenant,String key,int version,int revision,String previewId) {
        target(tenant,key);
        tx.executeWithoutResult(s->{
            var v=version(tenant,key,version);var provider=runtime(tenant,(String)v.get("tool_key"));
            var valid=store.jdbc.queryForList("SELECT id FROM capture_web_run WHERE tenant_id=? AND target_key=? AND id=? AND rule_version=? AND provider_revision=? AND run_mode='PREVIEW' AND status='SUCCEEDED' AND finished_at>=?",tenant,key,previewId,version,provider.revision(),Timestamp.from(store.clock.instant().minusSeconds(86400)));
            if(valid.isEmpty())throw new CaptureException("PREVIEW_REQUIRED","请先对该版本完成真实试抓；须24小时内成功且服务配置未变更");
            if(!provider.enabled())throw new CaptureException("FIRECRAWL_NOT_CONFIGURED","抓取服务已关闭");
            int changed=store.jdbc.update("UPDATE capture_web_rule SET published_version=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND target_key=? AND revision=?",version,store.now(),tenant,key,revision);
            if(changed!=1)throw conflict();
            var rule=decodeRule(v);store.jdbc.update("UPDATE capture_target_config SET source_url=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND target_key=?",rule.url(),store.now(),tenant,key);
        });return rules(tenant,key);
    }
    private Map<String,Object> version(String tenant,String key,int number) {
        var rows=store.jdbc.queryForList("SELECT * FROM capture_web_rule_version WHERE tenant_id=? AND target_key=? AND version=?",tenant,key,number);
        if(rows.isEmpty())throw new CaptureException("RULE_NOT_FOUND","规则版本不存在");return rows.getFirst();
    }
    private WebCaptureRule decodeRule(Map<String,Object> v){return store.mapper.convertValue(store.read(v.get("rule_json")),WebCaptureRule.class);}
    public Map<String,Object> state(String tenant,String key) {
        var states=store.jdbc.queryForList("SELECT draft_version,published_version,revision FROM capture_web_rule WHERE tenant_id=? AND target_key=?",tenant,key);
        return states.isEmpty()?Map.of("draft_version",0,"revision",0):states.getFirst();
    }
    public List<Map<String,Object>> runs(String tenant,String key) {
        var rows=key==null?store.jdbc.queryForList("SELECT id,target_key,rule_version,provider_revision,tool_key,run_mode,status,error_code,source_url,started_at,finished_at FROM capture_web_run WHERE tenant_id=? ORDER BY started_at DESC LIMIT 100",tenant):store.jdbc.queryForList("SELECT id,target_key,rule_version,provider_revision,tool_key,run_mode,status,error_code,source_url,started_at,finished_at FROM capture_web_run WHERE tenant_id=? AND target_key=? ORDER BY started_at DESC LIMIT 100",tenant,key);
        return rows;
    }
    public Map<String,Object> detail(String tenant,String id) {
        var rows=store.jdbc.queryForList("SELECT * FROM capture_web_run WHERE tenant_id=? AND id=?",tenant,id);if(rows.isEmpty())throw new CaptureException("WEB_RUN_NOT_FOUND","网页运行不存在");
        var r=rows.getFirst();r.remove("tenant_id");r.remove("active_scope");r.remove("lease_until");r.put("result_json",store.read(r.get("result_json")));return r;
    }
    public Map<String,Object> execute(String tenant,String key,Integer previewVersion) {
        target(tenant,key);int number;
        if(previewVersion!=null)number=previewVersion;else {
            Object published=state(tenant,key).get("published_version");if(published==null)throw new CaptureException("RULE_NOT_PUBLISHED","请先保存、试抓并发布规则");number=((Number)published).intValue();
        }
        var v=version(tenant,key,number);var rule=decodeRule(v);String tool=(String)v.get("tool_key");var provider=runtime(tenant,tool);String id=UUID.randomUUID().toString();
        String scope=hash(tenant+":"+key);String mode=previewVersion==null?"LIVE":"PREVIEW";
        store.jdbc.update("UPDATE capture_web_run SET status='INTERRUPTED',error_code='LEASE_EXPIRED',active_scope=NULL,finished_at=? WHERE tenant_id=? AND target_key=? AND status='RUNNING' AND lease_until<?",store.now(),tenant,key,store.now());
        try{store.jdbc.update("INSERT INTO capture_web_run(id,tenant_id,target_key,rule_version,provider_revision,tool_key,run_mode,status,active_scope,lease_until,source_url,started_at) VALUES(?,?,?,?,?,?,?,'RUNNING',?,?,?,?)",id,tenant,key,number,provider.revision(),tool,mode,scope,Timestamp.from(store.clock.instant().plusSeconds(300)),rule.url(),store.now());}
        catch(org.springframework.dao.DuplicateKeyException e){throw new CaptureException("WEB_CAPTURE_RUNNING","该网站已有采集运行，请稍后查看结果");}
        try {
            var raw=transport.fetch(tool,rule,provider);Path relative=Path.of("web",id,"received.html");Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.write(file,raw.bytes(),StandardOpenOption.CREATE_NEW);
            store.jdbc.update("UPDATE capture_web_run SET raw_sha256=?,raw_locator=? WHERE tenant_id=? AND id=? AND status='RUNNING'",raw.sha256(),relative.toString().replace('\\','/'),tenant,id);
            var result=new WebFieldExtractor().extract(rule,raw);
            var bundle=Map.of("schemaVersion","web-observation-1","rule",rule,"ruleVersion",number,"tool",tool,"sourceUrl",rule.url(),"capturedAt",raw.capturedAt(),"rawSha256",raw.sha256(),"result",result);
            Files.writeString(file.resolveSibling("bundle.json"),store.json(bundle),StandardOpenOption.CREATE_NEW);
            int n=store.jdbc.update("UPDATE capture_web_run SET status=?,result_json=?,finished_at=?,active_scope=NULL WHERE tenant_id=? AND id=? AND status='RUNNING' AND lease_until>=?",result.status(),store.json(bundle),store.now(),tenant,id,store.now());
            if(n!=1)throw new CaptureException("WEB_LEASE_LOST","运行已过期，禁止提交结果");
        }catch(Exception e){store.jdbc.update("UPDATE capture_web_run SET status='FAILED',error_code=?,finished_at=?,active_scope=NULL WHERE tenant_id=? AND id=? AND status='RUNNING'",RankingCaptureRunner.errorCode(e),store.now(),tenant,id);}
        return detail(tenant,id);
    }
    public List<Map<String,Object>> runEnabled(String tenant) {
        var targets=store.targets(tenant).stream().filter(t->dynamic(t)&&Boolean.TRUE.equals(t.get("enabled"))).toList();
        if(targets.size()>5)throw new CaptureException("WEB_TARGET_LIMIT","首版每批最多启用5个在线规则目标");
        var results=new ArrayList<Map<String,Object>>();
        for(var t:targets)try{results.add(execute(tenant,(String)t.get("target_key"),null));}catch(CaptureException e){results.add(Map.of("target_key",t.get("target_key"),"status","FAILED","error_code",e.code()));}
        return results;
    }
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static CaptureException conflict(){return new CaptureException("CONFIG_CONFLICT","配置已被其他请求修改，请刷新后再操作");}
}
