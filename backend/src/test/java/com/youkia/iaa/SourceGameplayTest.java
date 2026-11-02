package com.youkia.iaa;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.service.SourceGameplay;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceGameplayTest {
 final ObjectMapper mapper=new ObjectMapper();
 Map<String,Object> read(String description){return SourceGameplay.read(mapper.valueToTree(Map.of("tags",List.of(),"descriptionRaw",description)));}
 @Test void explicitDescriptionKeepsOriginalQuoteAndUnverifiedState(){
  String quote="古风消除头部产品，DAU超过百万（来源自述）";
  var r=read(quote);assertEquals("消除",r.get("family"));assertEquals("SOURCE_DESCRIPTION",r.get("basis"));assertEquals(quote,r.get("quote"));assertEquals(false,r.get("verified"));assertFalse(r.containsKey("dau"));
  assertEquals("合成",read("二合赛道头部产品").get("family"));assertEquals("解谜",read("经典华容道玩法").get("family"));
 }
 @Test void promotionalUnknownNegativeAndComplexClaimsRemainUnknown(){
  for(String s:List.of("","月GMV超过千万","不是消除","这是一款重度SLG，含消除玩法","不属于合成"))assertEquals("UNKNOWN",read(s).get("basis"),s);
 }
 @Test void explicitTagsAreAttributedToSource(){
  var r=SourceGameplay.read(mapper.valueToTree(Map.of("tags",List.of("休闲","塔防"),"descriptionRaw","")));
  assertEquals("塔防",r.get("family"));assertEquals("SOURCE_TAG",r.get("basis"));assertEquals(false,r.get("verified"));
 }
}
