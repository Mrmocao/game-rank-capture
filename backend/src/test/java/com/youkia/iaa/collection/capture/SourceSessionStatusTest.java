package com.youkia.iaa.collection.capture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SourceSessionStatusTest {
 @Test void failuresPersistUntilSuccessfulCaptureAndRemainTenantScoped(){
  var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  jdbc.execute("CREATE TABLE external_capture_run(id VARCHAR(40),tenant_id VARCHAR(40),source_key VARCHAR(40),chart_key VARCHAR(40),region VARCHAR(8),status VARCHAR(32),error_code VARCHAR(80),started_at TIMESTAMP,finished_at TIMESTAMP)");
  var service=new SourceSessionStatus(jdbc,"a");assertEquals(false,service.read().get("requiresLogin"));
  row(jdbc,"b","FAILED","SOURCE_AUTH_REQUIRED",1);assertEquals(false,service.read().get("requiresLogin"));
  row(jdbc,"a","PARTIAL",null,2);assertEquals(false,service.read().get("requiresLogin"));
  row(jdbc,"a","FAILED","SOURCE_AUTH_REQUIRED",3);assertEquals(true,service.read().get("requiresLogin"));
  Object first=service.read().get("event");row(jdbc,"a","FAILED","SOURCE_TIMEOUT",4);row(jdbc,"a","FAILED","SOURCE_AUTH_REQUIRED",5);
  assertEquals(first,service.read().get("event"));assertEquals(true,service.read().get("requiresLogin"));
  row(jdbc,"a","SUCCEEDED",null,6);assertEquals(false,service.read().get("requiresLogin"));
  row(jdbc,"a","FAILED","SOURCE_AUTH_REQUIRED",7);assertEquals(true,service.read().get("requiresLogin"));assertNotEquals(first,service.read().get("event"));
 }
 void row(JdbcTemplate db,String tenant,String status,String code,int day){db.update("INSERT INTO external_capture_run VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),tenant,"gravity-wechat","bestseller","cn",status,code,"2026-11-"+String.format("%02d",day)+" 00:00:00",null);}
 @Test void maintenanceCodeIsRequiredRateLimitedAndExpires(){
  var now=new AtomicReference<>(Instant.parse("2026-11-16T00:00:00Z"));
  Clock clock=new Clock(){public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
  String secret="test-maintenance-access-32-characters";var access=new CaptureLoginAccess(secret,clock);
  assertFalse(access.valid(null));assertFalse(new CaptureLoginAccess("",clock).configured());
  for(int i=0;i<10;i++)assertNull(access.login("wrong"));assertNull(access.login(secret));
  now.set(now.get().plusSeconds(901));String token=access.login(secret);assertNotNull(token);assertTrue(access.valid(token));assertFalse(access.valid(secret));
  now.set(now.get().plusSeconds(1801));assertFalse(access.valid(token));
 }
}
