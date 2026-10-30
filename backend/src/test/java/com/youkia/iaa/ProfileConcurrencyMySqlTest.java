package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.*;
import com.youkia.iaa.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Two actual database transactions race; a unique synthetic workspace is removed in finally. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_MYSQL_TESTS",matches="true")
class ProfileConcurrencyMySqlTest {
    @Test void taskPlanningSelectionRetryAndRevisionAreAtomic()throws Exception{
        var env=new HashMap<String,String>();for(String l:Files.readAllLines(Path.of("../.env"))){int i=l.indexOf('=');if(i>0&&!l.startsWith("#"))env.put(l.substring(0,i),l.substring(i+1));}
        var ds=new DriverManagerDataSource(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"));var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var mapper=new ObjectMapper().findAndRegisterModules();String tenant="SYNTHETIC-RACE-"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);
        var row=jdbc.queryForMap("SELECT id,product_id FROM platform_version WHERE external_id NOT LIKE 'demo-%' LIMIT 1");long entity=((Number)row.get("id")).longValue(),product=((Number)row.get("product_id")).longValue();LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        try{
            jdbc.update("INSERT INTO market_listing(tenant_id,platform_version_id,market,language,first_observed_at,available_at) VALUES(?,?,'CN','zh-CN',?,?)",tenant,entity,day.minusDays(1).atStartOfDay(),day.minusDays(1).atStartOfDay());long listing=jdbc.queryForObject("SELECT id FROM market_listing WHERE tenant_id=?",Long.class,tenant);var profiles=new GameProfileService(jdbc,mapper,tenant);
            var plans=race(pool,()->tx.execute(s->profiles.plan(listing,day,"Asia/Shanghai")));int created=plans.stream().mapToInt(p->((Number)p.get("created")).intValue()).sum();assertEquals(profiles.tasks(listing).size(),created);assertEquals(12,created);
            var selections=new SelectionService(jdbc,mapper,new ProductQueryService(jdbc,tenant),tenant);var request=new SelectionRequest("SYNTHETIC并发甲",entity,"SELECTED",day,null,null,"SYNTHETIC并发测试",listing);
            var responses=race(pool,()->tx.execute(s->selections.create(product,request,"SYNTHETIC-retry")));assertEquals(responses.get(0).get("id").toString(),responses.get(1).get("id").toString());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM selection_record WHERE tenant_id=?",Integer.class,tenant));
            long id=Long.parseLong(responses.getFirst().get("id").toString());var patch=new SelectionPatch("SYNTHETIC并发甲",entity,"IN_PROGRESS",day,day,null,"SYNTHETIC更新",0,listing);
            var updates=race(pool,()->{try{tx.execute(s->selections.update(id,patch));return true;}catch(ApiException e){assertEquals("VERSION_CONFLICT",e.code());return false;}});assertEquals(1,updates.stream().filter(Boolean.TRUE::equals).count());
        }finally{pool.shutdownNow();tx.executeWithoutResult(s->{jdbc.update("DELETE FROM profile_enrichment_task WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM selection_record WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM audit_event WHERE actor_id=?","workspace:"+tenant);jdbc.update("DELETE FROM idempotency_record WHERE actor_id=?","workspace:"+tenant);jdbc.update("DELETE FROM market_listing WHERE tenant_id=?",tenant);});}
    }
    <T> List<T> race(ExecutorService pool,Supplier<T> action)throws Exception{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return action.get();});var b=pool.submit(()->{start.await();return action.get();});start.countDown();return List.of(a.get(45,TimeUnit.SECONDS),b.get(45,TimeUnit.SECONDS));}
}
