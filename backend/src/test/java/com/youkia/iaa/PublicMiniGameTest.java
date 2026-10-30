package com.youkia.iaa;
import com.youkia.iaa.collection.PublicMiniGameService;
import com.youkia.iaa.collection.SnapshotMediaService;
import com.youkia.iaa.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PublicMiniGameTest {
 private final PublicMiniGameService service=new PublicMiniGameService(null,new ObjectMapper(),"unused");
 @Test void publicMaterialPreservesScopeAndTwentyRows()throws Exception{
  for(String platform:new String[]{"WECHAT","DOUYIN"}){
   var m=service.validate(platform,Files.readString(Path.of("../validation/source-research/gravity-"+platform+".html")));
   assertEquals(20,m.entries().size());assertEquals(1,m.entries().getFirst().get("rank"));assertEquals(20,m.entries().getLast().get("rank"));
   assertEquals("UNVERIFIED",m.entries().getFirst().get("identityState"));assertNotNull(m.entries().getFirst().get("thumbnail_url"));
  }
 }
 @Test void platformPeriodAndBrokenRanksRejected()throws Exception{
  String raw=Files.readString(Path.of("../validation/source-research/gravity-WECHAT.html"));
  assertThrows(ApiException.class,()->service.validate("DOUYIN",raw));
  assertThrows(ApiException.class,()->service.validate("WECHAT",raw.replace("NO.4","NO.99")));
  assertThrows(ApiException.class,()->service.validate("WECHAT","<html>Login</html>"));
 }
 @Test void unsafeImageUrlsAreNotRendered(){assertNull(SnapshotMediaService.safeUrl("javascript:alert(1)"));assertNull(SnapshotMediaService.safeUrl("data:image/svg+xml,stuff"));assertNull(SnapshotMediaService.safeUrl(null));assertEquals("https://example.com/icon.png",SnapshotMediaService.safeUrl("https://example.com/icon.png"));}
}
