package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** The shipped Skill and its executable policy are frozen together per report. */
public final class MarketWeeklySkill {
    public final String instructions;
    public final JsonNode policy;
    public final String sha256;
    public MarketWeeklySkill(ObjectMapper mapper) {
        try {
            instructions=new ClassPathResource("skills/market-weekly/SKILL.md").getContentAsString(StandardCharsets.UTF_8);
            String raw=new ClassPathResource("skills/market-weekly/policy.json").getContentAsString(StandardCharsets.UTF_8);
            policy=mapper.readTree(raw);sha256=hash(instructions+"\n"+raw);
            int max = policy.path("maxPicks").asInt();
            if(max != 5)throw new IllegalArgumentException("每期精选最多5项；不足不补齐");
        } catch(Exception e) {throw new IllegalStateException("精选Skill加载失败",e);}
    }
    public Map<String,Object> snapshot(){return Map.of("name","market-weekly","version",policy.path("version").asText(),"sha256",sha256,"instructions",instructions,"policy",policy,"execution","JAVA_DETERMINISTIC");}
    public String keywords(String text){List<String> hits=new ArrayList<>();String lower=text.toLowerCase(Locale.ROOT);policy.path("keywords").forEach(k->{if(lower.contains(k.asText()))hits.add(k.asText());});return String.join("、",hits);}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
