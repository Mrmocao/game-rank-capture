package com.youkia.iaa.service;

import com.youkia.iaa.api.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class RuleService {
    private final JdbcTemplate jdbc; public RuleService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> current(){try{Map<String,Object> r=jdbc.queryForMap("SELECT id,version_number,status,rules_json,content_sha256,published_at FROM rule_version WHERE status='PUBLISHED' ORDER BY version_number DESC LIMIT 1");Map<String,Object> out=new LinkedHashMap<>();out.put("id",String.valueOf(r.get("id")));out.put("versionNumber",r.get("version_number"));out.put("status",r.get("status"));out.put("rulesJson",r.get("rules_json"));out.put("contentSha256",r.get("content_sha256"));out.put("publishedAt",r.get("published_at"));return out;}catch(EmptyResultDataAccessException e){return Map.of("status","NONE","rulesJson","{}");}}
    @Transactional public Map<String,Object> publish(RulePublishRequest req){try{new com.fasterxml.jackson.databind.ObjectMapper().readTree(req.rulesJson());}catch(Exception e){throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_RULE_JSON","规则必须是合法 JSON");}int next=Optional.ofNullable(jdbc.queryForObject("SELECT MAX(version_number) FROM rule_version",Integer.class)).orElse(0)+1;String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(req.rulesJson().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}jdbc.update("UPDATE rule_version SET status='SUPERSEDED' WHERE status='PUBLISHED'");jdbc.update("INSERT INTO rule_version(version_number,status,rules_json,content_sha256,published_at,created_by,created_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3))",next,"PUBLISHED",req.rulesJson(),hash,"dev-user");return current();}
}
