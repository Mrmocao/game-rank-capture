package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.service.WeeklyReportService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable MySQL schema; synthetic fixtures never enter configured business database. */
@EnabledIfEnvironmentVariable(named="RUN_WEEKLY_RETENTION_MYSQL_TESTS",matches="true")
class WeeklyReportRetentionMySqlTest {
    @Test void migrationReplacementRollbackAndConcurrentGeneration() throws Exception {
        var env=new HashMap<String,String>();
        for(String l:Files.readAllLines(Path.of("../.env"))){int p=l.indexOf('=');if(p>0&&!l.startsWith("#"))env.put(l.substring(0,p),l.substring(p+1));}
        String db="iaa_weekly_test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD")));
        admin.execute("CREATE DATABASE "+db+" CHARACTER SET utf8mb4");
        try{
            String url=env.get("DB_URL").replaceFirst("(jdbc:mysql://[^/]+/)[^?]+","$1"+db);
            var ds=new DriverManagerDataSource(url,env.get("DB_USERNAME"),env.get("DB_PASSWORD"));
            var j=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            Flyway.configure().dataSource(ds).target("22").load().migrate();
            j.update("INSERT INTO rule_version(version_number,status,rules_json,content_sha256,created_by,created_at) VALUES(1,'PUBLISHED','{}',?,'SYNTHETIC-TEST',UTC_TIMESTAMP())","0".repeat(64));
            long rule=j.queryForObject("SELECT MAX(id) FROM rule_version",Long.class);
            // Same calendar week, different day windows and revisions, plus a different week.
            j.update("INSERT INTO weekly_report(id,period_start,period_end,revision,status,rule_version_id,coverage_json,generated_at) VALUES(9001,'2001-01-01','2001-01-02',1,'PUBLISHED',?,'{}','2001-01-02'),(9002,'2001-01-01','2001-01-04',1,'PUBLISHED',?,'{}','2001-01-04'),(9003,'2001-01-08','2001-01-09',1,'PUBLISHED',?,'{}','2001-01-09')",rule,rule,rule);
            addItem(j,9001);addItem(j,9002);
            Flyway.configure().dataSource(ds).load().migrate();
            assertEquals(2,j.queryForObject("SELECT COUNT(*) FROM weekly_report",Integer.class));
            assertEquals(9002L,j.queryForObject("SELECT report_id FROM weekly_report_alias WHERE old_report_id=9001",Long.class));
            assertEquals(1,j.queryForObject("SELECT COUNT(*) FROM report_item",Integer.class));
            var mapper=new ObjectMapper().findAndRegisterModules();var service=new WeeklyReportService(j,mapper);
            assertEquals("9002",service.report(9001).get("id"));
            String untouched=j.queryForObject("SELECT CONCAT(id,period_end,revision,coverage_json) FROM weekly_report WHERE id=9003",String.class);
            LocalDate start=LocalDate.parse("2001-01-01"),end=LocalDate.parse("2001-01-05");
            var first=tx.execute(s->service.generate(start,end));
            assertEquals("9002",first.get("id"));assertEquals(true,first.get("replaced"));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM report_item WHERE report_id=9002",Integer.class),"old selected items must be removed");
            var same=tx.execute(s->service.generate(start,end));
            assertEquals(first.get("revision"),same.get("revision"));assertEquals(true,same.get("reused"));
            var changed=tx.execute(s->service.generate(start,end.plusDays(1)));
            assertEquals(first.get("id"),changed.get("id"));assertEquals(3,changed.get("revision"));
            assertEquals(2,j.queryForObject("SELECT COUNT(*) FROM weekly_report",Integer.class));
            assertEquals(untouched,j.queryForObject("SELECT CONCAT(id,period_end,revision,coverage_json) FROM weekly_report WHERE id=9003",String.class));
            // Inject failure after the UPDATE has executed: transaction must restore previous content.
            var failingJdbc=new JdbcTemplate(ds){@Override public int update(String sql,Object... args){int n=super.update(sql,args);if(sql.stripLeading().startsWith("UPDATE weekly_report SET"))throw new IllegalStateException("synthetic failure after update");return n;}};
            assertThrows(IllegalStateException.class,()->tx.execute(s->new WeeklyReportService(failingJdbc,mapper).generate(start,end.plusDays(2))));
            assertEquals(changed.get("inputSha256"),service.report(9002).get("inputSha256"));
            // Two real concurrent transactions targeting an empty week share a single persistent ID.
            try(var pool=Executors.newFixedThreadPool(2)){
                var gate=new CountDownLatch(1);
                Callable<Map<String,Object>> call=()->{gate.await();return tx.execute(s->service.generate(LocalDate.parse("2001-01-15"),LocalDate.parse("2001-01-17")));};
                var a=pool.submit(call);var b=pool.submit(call);gate.countDown();
                var ra=a.get(90,TimeUnit.SECONDS);var rb=b.get(90,TimeUnit.SECONDS);
                assertEquals(ra.get("id"),rb.get("id"));
                assertNotEquals(ra.get("reused"),rb.get("reused"));
                assertEquals(3,j.queryForObject("SELECT COUNT(*) FROM weekly_report",Integer.class));
            }
            assertEquals(3L,service.history(1,20).get("total"));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM (SELECT week_start FROM weekly_report GROUP BY week_start HAVING COUNT(*)>1) d",Integer.class));
        }finally{admin.execute("DROP DATABASE "+db);}
    }
    private static void addItem(JdbcTemplate j,long report){
        j.update("INSERT INTO report_item(report_id,product_id,main_reference_version_id,section,display_order,frozen_facts,evidence_refs,content) VALUES(?,1001,2001,'DOMESTIC',1,'{}','[]','SYNTHETIC migration fixture')",report);
    }
}
