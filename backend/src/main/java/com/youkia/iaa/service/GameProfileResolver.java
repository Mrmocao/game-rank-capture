package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;

/** Pure as-known-at field resolver. Drafts and later knowledge never certify eligibility. */
public final class GameProfileResolver {
    private GameProfileResolver(){}
    public static Map<String,Object> resolve(JsonNode definition,List<Map<String,Object>> history,LocalDateTime cutoff,Long currentRelease){
        String key=definition.path("key").asText();var out=new LinkedHashMap<String,Object>();
        out.put("key",key);out.put("label",definition.path("label").asText());out.put("domain",definition.path("domain").asText());out.put("tab",definition.path("tab").asText());out.put("type",definition.path("type").asText());out.put("layer",definition.path("layer").asText());
        var visible=history.stream().filter(x->!time(x.get("observedAt")).isAfter(cutoff)&&!time(x.get("availableAt")).isAfter(cutoff)).toList();
        Set<String> superseded=new HashSet<>();visible.forEach(x->{if(x.get("supersedesId")!=null)superseded.add(x.get("supersedesId").toString());});
        var facts=visible.stream().filter(x->!"inference".equals(x.get("kind"))&&!superseded.contains(String.valueOf(x.get("id")))).toList();
        var current=new ArrayList<Map<String,Object>>();boolean stale=false;
        for(var h:facts){
            boolean wrongRelease=definition.path("versionSensitive").asBoolean()&&(currentRelease==null||!Objects.equals(String.valueOf(currentRelease),String.valueOf(h.get("releaseId"))));
            if(wrongRelease||h.get("validUntil")!=null&&time(h.get("validUntil")).isBefore(cutoff)){stale=true;continue;}
            current.add(h);
        }
        var available=current.stream().filter(x->"available".equals(x.get("status"))&&x.get("value")!=null&&trustedRights(x)).toList();
        Set<String> values=new HashSet<>();available.forEach(x->values.add(String.valueOf(x.get("value"))));
        boolean conflict=current.stream().anyMatch(x->"conflict".equals(x.get("status")))||values.size()>1;
        if(conflict){out.put("status","conflict");out.put("value",null);out.put("reason","有效证据互相矛盾，等待显式复核裁决");out.put("conflictingObservations",current);}
        else if(!available.isEmpty()){out.putAll(available.getLast());out.put("status","available");}
        else{
            var draft=visible.stream().filter(x->"inference".equals(x.get("kind"))&&"available".equals(x.get("status"))&&trustedRights(x)&&(!definition.path("versionSensitive").asBoolean()||currentRelease!=null&&String.valueOf(currentRelease).equals(String.valueOf(x.get("releaseId"))))&&(x.get("validUntil")==null||!time(x.get("validUntil")).isBefore(cutoff))).toList();
            // A draft cannot conceal expiry, an explicit gap, or a negative fact.
            if(!draft.isEmpty()&&current.isEmpty()&&!stale){out.putAll(draft.getLast());out.put("status","available");out.put("verificationStatus","draft");}
            else if(!current.isEmpty()){var h=current.getLast();out.putAll(h);out.put("value",null);out.put("status",!trustedRights(h)?"not_authorized":h.get("status"));}
            else{out.put("status",stale?"stale":visible.isEmpty()&&!history.isEmpty()?"not_yet_known":"missing");out.put("value",null);out.put("reason",stale?"旧核查版本已变化或证据过期":"尚无该范围的有效资料");}
        }
        out.put("historyCount",visible.size());return out;
    }
    private static boolean trustedRights(Map<String,Object> x){return !"not_authorized".equals(x.get("rights"));}
    public static LocalDateTime time(Object value){if(value instanceof LocalDateTime d)return d;if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime();String s=String.valueOf(value);return s.length()==10?LocalDate.parse(s).atStartOfDay():LocalDateTime.parse(s.replace(' ','T').replace("Z",""));}
    public static Map<String,Object> coverage(Map<String,Map<String,Object>> fields){
        long na=fields.values().stream().filter(x->"not_applicable".equals(x.get("status"))).count();
        long readable=fields.values().stream().filter(x->"available".equals(x.get("status"))).count();
        long drafts=fields.values().stream().filter(x->"available".equals(x.get("status"))&&"inference".equals(x.get("kind"))).count();
        return Map.of("readable",readable,"applicable",fields.size()-na,"aiDrafts",drafts,"missing",fields.size()-na-readable,"notApplicable",na,"notice","资料量仅表示适用字段可读程度，不是机会分、数据可信度或成功概率");
    }
    public static Map<String,Object> qualification(Map<String,Map<String,Object>> fields){
        var mode=fields.get("mode");String status=String.valueOf(mode.get("status"));Object value=mode.get("value");
        if(status.equals("conflict")||status.equals("stale"))return Map.of("status",status,"label","待重新核查","eligible",false);
        if("available".equals(status)&&!"inference".equals(mode.get("kind"))&&Set.of("mixed","IAP").contains(String.valueOf(value)))return Map.of("status","mixed","label","混合/内购变现","eligible",false);
        boolean eligible="IAA".equals(value)&&"available".equals(status)&&!"inference".equals(mode.get("kind"))&&Boolean.TRUE.equals(mode.get("reviewed"));
        for(String k:List.of("no_iap_basis","monetization_valid","ad_formats")){
            var f=fields.get(k);eligible&=f!=null&&"available".equals(f.get("status"))&&!"inference".equals(f.get("kind"))&&Boolean.TRUE.equals(f.get("reviewed"));
        }
        var iap=fields.get("iap_basis");if(iap!=null&&"conflict".equals(iap.get("status")))eligible=false;
        return Map.of("status",eligible?"IAA":"unknown","label",eligible?"纯IAA有依据":"变现待核查","eligible",eligible);
    }
}
