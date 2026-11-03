package com.youkia.iaa.service;
import java.util.*;

/** One canonical source identity shared by review, source cards and weekly research. */
public final class SourceEntity {
    private SourceEntity(){}
    public static String key(Map<String,Object> r){
        String id=text(r,"external_id"),publisher=text(r,"developer");
        return text(r,"source_key")+":"+text(r,"platform")+":"+text(r,"region").toLowerCase(Locale.ROOT)+":"+
            (!id.isBlank()?"id:"+id:"label:"+text(r,"name")+":"+publisher+(publisher.isBlank()?":observation:"+r.get("observation_id"):""));
    }
    public static String sha(Map<String,Object> r){return MarketWeeklySkill.hash(key(r));}
    public static String text(Map<String,Object> r,String k){return Objects.toString(r.get(k),"");}
}
