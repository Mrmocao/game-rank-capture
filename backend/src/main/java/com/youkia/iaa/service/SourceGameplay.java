package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Extract explicit source words only. Never infers gameplay from a title or monetization from popularity. */
public final class SourceGameplay {
    public static final String VERSION="source-gameplay-1.0.0";
    private static final Map<String,List<String>> WORDS=new LinkedHashMap<>();
    static {
        WORDS.put("消除",List.of("消除","三消"));WORDS.put("合成",List.of("合成","二合"));
        WORDS.put("塔防",List.of("塔防"));WORDS.put("跑酷",List.of("跑酷"));
        WORDS.put("解谜",List.of("解谜","找茬","华容道","挪车"));WORDS.put("益智",List.of("益智","拼图"));
        WORDS.put("模拟经营",List.of("模拟经营","经营类","餐厅经营","商场经营"));
    }
    public static Map<String,Object> read(JsonNode fields){
        String tags=fields.path("tags").isArray()?fields.path("tags").toString():"";
        String description=fields.path("descriptionRaw").asText("");
        for(var entry:WORDS.entrySet())for(String word:entry.getValue())if(tags.contains(word))return result(entry.getKey(),"SOURCE_TAG",word,tags);
        // Mixed/complex descriptions require manual classification; a casual keyword is not sufficient.
        if(!description.matches("(?is).*(SLG|MMORPG|重度|大型角色扮演|策略卡牌).*"))
            for(var entry:WORDS.entrySet())for(String word:entry.getValue()){
                int start=description.indexOf(word);
                if(start>=0&&!description.substring(Math.max(0,start-4),start).matches(".*(不是|不含|不属于|非|并非|无)$"))
                    return result(entry.getKey(),"SOURCE_DESCRIPTION",word,description);
            }
        return result("未知","UNKNOWN","",description);
    }
    private static Map<String,Object> result(String family,String basis,String word,String quote){
        return Map.of("family",family,"basis",basis,"matchedText",word,"quote",quote,"verified",false,"version",VERSION,
                "label",basis.equals("SOURCE_TAG")?"来源标签，待实机核验":basis.equals("SOURCE_DESCRIPTION")?"来源描述明确提及，待实机核验":"描述不足，待识别");
    }
}
