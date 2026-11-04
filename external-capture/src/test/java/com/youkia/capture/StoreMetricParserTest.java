package com.youkia.capture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.metrics.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class StoreMetricParserTest {
 final StoreMetricParser parser=new StoreMetricParser(new ObjectMapper());
 String fixture(String name)throws Exception{return new String(getClass().getResourceAsStream("/metrics/"+name).readAllBytes(),StandardCharsets.UTF_8);}
 GameMetric.Snapshot parse(MetricTarget t,String html){return parser.parse(t,new RawMaterial(t.url(),Instant.parse("2026-11-04T00:00:00Z"),"fixture",html.getBytes(StandardCharsets.UTF_8)));}
 @Test void officialPriceIsNotDownloadsOrRevenue()throws Exception {
  var s=parse(new MetricTarget("IOS","6755228041","cn","apple-lookup"),fixture("apple-lookup.json"));
  assertEquals(3,s.metrics().size());var price=s.metrics().getFirst();assertEquals("price",price.key());assertEquals("CNY",price.currency());assertEquals("cn",price.geographicScope());assertEquals("30",price.value().toPlainString());
  assertTrue(s.metrics().stream().noneMatch(v->v.key().contains("revenue")||v.key().equals("installs")));
 }
 @Test void applePurchasesArePartialEvidenceNotSales()throws Exception {
  var s=parse(new MetricTarget("IOS","989673964","cn","apple-detail"),fixture("apple-detail.html"));
  assertEquals(1,s.purchases().size());assertEquals("60点券",s.purchases().getFirst().name());assertTrue(s.metrics().stream().anyMatch(v->v.key().equals("has_iap")));assertFalse(s.limitations().isEmpty());
 }
 @Test void googleRangeHasNoInventedExactValueOrCountry()throws Exception {
  var s=parse(new MetricTarget("GOOGLE_PLAY","com.example.game","us","google-detail"),fixture("google-detail.html"));
  var installs=s.metrics().stream().filter(v->v.key().equals("installs")).findFirst().orElseThrow();assertEquals("500M+",installs.rawDisplay());assertNull(installs.value());assertEquals("GLOBAL",installs.geographicScope());assertEquals("LIFETIME",installs.period());assertEquals("STORE_RANGE",installs.method());
  assertTrue(s.metrics().stream().anyMatch(v->v.key().equals("has_iap")));assertTrue(s.metrics().stream().anyMatch(v->v.key().equals("contains_ads")));
 }
 @Test void missingLabelsStayUnknownEvenWhenReviewMentionsPurchases()throws Exception {
  var html=fixture("google-detail.html").replace("<span>In-app purchases</span>","").replace("<span>Contains ads</span>","")+"<article><span>In-app purchases</span></article>";
  var s=parse(new MetricTarget("GOOGLE_PLAY","com.example.game","us","google-detail"),html);assertTrue(s.metrics().stream().noneMatch(v->v.key().equals("has_iap")||v.key().equals("contains_ads")));
 }
 @Test void identityRegionLoginAndInvalidTargetsFailClosed()throws Exception {
  var t=new MetricTarget("IOS","989673964","cn","apple-detail");var html=fixture("apple-detail.html");
  assertThrows(CaptureException.class,()->parse(t,html.replace("/cn/app/","/us/app/")));assertThrows(CaptureException.class,()->parse(t,html.replace("id989673964","id123456789")));assertThrows(CaptureException.class,()->parse(t,"<html>Log in</html>"));
  var g=new MetricTarget("GOOGLE_PLAY","com.wrong.game","us","google-detail");assertThrows(CaptureException.class,()->parse(g,fixture("google-detail.html")));
  assertThrows(CaptureException.class,()->new MetricTarget("WECHAT","12345","cn","apple-detail"));assertThrows(CaptureException.class,()->new MetricTarget("IOS",null,"cn","apple-detail"));assertThrows(CaptureException.class,()->new MetricTarget("IOS","12345",null,"apple-detail"));
 }
 @Test void nonGameLookupAndWrongMaterialCannotEnter()throws Exception {
  var t=new MetricTarget("IOS","6755228041","cn","apple-lookup");assertThrows(CaptureException.class,()->parse(t,fixture("apple-lookup.json").replace("6014","6000")));
  assertThrows(CaptureException.class,()->parser.parse(t,new RawMaterial(java.net.URI.create("https://example.com"),Instant.now(),"fixture",new byte[]{1})));
 }
}
