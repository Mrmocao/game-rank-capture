package com.youkia.iaa.service;

import com.youkia.iaa.api.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AiConfigService {
    private final JdbcTemplate jdbc; private final SecretCipher cipher; private final String envBaseUrl; private final String envModel; private final String envApiKey;
    public AiConfigService(JdbcTemplate jdbc,SecretCipher cipher,@Value("${AI_BASE_URL:}") String envBaseUrl,@Value("${AI_MODEL:}") String envModel,@Value("${AI_API_KEY:}") String envApiKey){this.jdbc=jdbc;this.cipher=cipher;this.envBaseUrl=envBaseUrl;this.envModel=envModel;this.envApiKey=envApiKey;}
    public Map<String,Object> get(){try{return normalize(jdbc.queryForMap("SELECT id,provider_name,base_url,model,api_key_configured,timeout_seconds,temperature,enabled,updated_at,version FROM ai_provider_config WHERE id=1"));}catch(EmptyResultDataAccessException e){Map<String,Object> m=new LinkedHashMap<>();m.put("id",1);m.put("providerName",envBaseUrl.isBlank()?"":"ENV OpenAI-compatible");m.put("baseUrl",envBaseUrl);m.put("model",envModel);m.put("apiKeyConfigured",!envApiKey.isBlank());m.put("timeoutSeconds",60);m.put("temperature",0.2);m.put("enabled",!envBaseUrl.isBlank()&&!envModel.isBlank()&&!envApiKey.isBlank());m.put("version",0);return normalize(m);}}
    @Transactional public Map<String,Object> save(AiConfigRequest req){String encrypted=null;boolean configured=false;try{Map<String,Object> old=jdbc.queryForMap("SELECT api_key_ciphertext,api_key_configured FROM ai_provider_config WHERE id=1");encrypted=(String)old.get("api_key_ciphertext");configured=Boolean.TRUE.equals(old.get("api_key_configured"));}catch(EmptyResultDataAccessException ignored){}
        if(req.apiKey()!=null&&!req.apiKey().isBlank()){encrypted=cipher.encrypt(req.apiKey());configured=true;}
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);jdbc.update("INSERT INTO ai_provider_config(id,provider_name,base_url,model,api_key_ciphertext,api_key_configured,timeout_seconds,temperature,enabled,created_at,updated_at,version) VALUES(1,?,?,?,?,?,?,?,?,?,?,0) ON DUPLICATE KEY UPDATE provider_name=VALUES(provider_name),base_url=VALUES(base_url),model=VALUES(model),api_key_ciphertext=VALUES(api_key_ciphertext),api_key_configured=VALUES(api_key_configured),timeout_seconds=VALUES(timeout_seconds),temperature=VALUES(temperature),enabled=VALUES(enabled),updated_at=VALUES(updated_at),version=version+1",req.providerName(),req.baseUrl(),req.model(),encrypted,configured,req.timeoutSeconds()==null?60:req.timeoutSeconds(),req.temperature()==null?0.2:req.temperature(),Boolean.TRUE.equals(req.enabled()),now,now);return get();}
    private Map<String,Object> normalize(Map<String,Object> raw){Map<String,Object> m=new LinkedHashMap<>();m.put("id",raw.get("id"));m.put("providerName",raw.getOrDefault("providerName",raw.get("provider_name")));m.put("baseUrl",raw.getOrDefault("baseUrl",raw.get("base_url")));m.put("model",raw.get("model"));m.put("apiKeyConfigured",raw.getOrDefault("apiKeyConfigured",raw.get("api_key_configured")));m.put("timeoutSeconds",raw.getOrDefault("timeoutSeconds",raw.get("timeout_seconds")));m.put("temperature",raw.get("temperature"));m.put("enabled",raw.get("enabled"));m.put("updatedAt",raw.get("updated_at"));m.put("version",raw.get("version"));return m;}
}
