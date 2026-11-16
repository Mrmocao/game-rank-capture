package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.ApiException;
import com.youkia.iaa.service.MarketTaxonomyService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL, disposable schema only. Never writes synthetic evidence to the business database. */
@EnabledIfEnvironmentVariable(named="RUN_TAXONOMY_MYSQL_TESTS",matches="true")
class MarketTaxonomyMySqlTest {
    @Test void migrationScopeValidationEvidenceReadAndIdempotency() throws Exception {
        var env=new HashMap<String,String>();
        for(String l:Files.readAllLines(Path.of("../.env"))){int p=l.indexOf('=');if(p>0&&!l.startsWith("#"))env.put(l.substring(0,p),l.substring(p+1));}
        String db="iaa_taxonomy_test_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD")));
        admin.execute("CREATE DATABASE "+db+" CHARACTER SET utf8mb4");
        try{
            var ds=new DriverManagerDataSource(env.get("DB_URL").replaceFirst("(jdbc:mysql://[^/]+/)[^?]+","$1"+db),env.get("DB_USERNAME"),env.get("DB_PASSWORD"));
            Flyway.configure().dataSource(ds).target("24").load().migrate();
            var j=new JdbcTemplate(ds);long products=j.queryForObject("SELECT COUNT(*) FROM product",Long.class);
            Flyway.configure().dataSource(ds).load().migrate();
            assertEquals(products,j.queryForObject("SELECT COUNT(*) FROM product",Long.class));
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'market_taxonomy_%' AND column_comment=''",Integer.class));
            var service=new MarketTaxonomyService(j,"synthetic-test");var mapper=new ObjectMapper();
            var term=mapper.readTree("{\"termType\":\"MECHANIC\",\"canonicalName\":\"SYNTHETIC机制\",\"definition\":\"仅测试，不是真实玩法证据\",\"actor\":\"测试\"}");
            long id=((Number)service.createTerm(term).get("id")).longValue();assertEquals(true,service.createTerm(term).get("reused"));
            var body=mapper.createObjectNode().put("actor","SYNTHETIC").put("scope","MARKET").put("market","CN").put("sourceName","SYNTHETIC").put("sourceUrl","https://example.com/test").put("excerpt","合成验证材料").put("observedDate","2026-10-01");
            var first=service.observe(id,body);var again=service.observe(id,body);assertEquals(first.get("id"),again.get("id"));assertEquals(true,again.get("reused"));
            assertEquals(1,service.observations(id,LocalDate.of(2026,10,1)).size());assertTrue(service.observations(id,LocalDate.of(2026,9,30)).isEmpty());
            assertTrue(new MarketTaxonomyService(j,"another-tenant").observations(id,LocalDate.now()).isEmpty());
            assertThrows(ApiException.class,()->new MarketTaxonomyService(j,"another-tenant").observe(id,body));
            body.put("scope","PRODUCT");assertThrows(ApiException.class,()->service.observe(id,body));
            body.put("scope","VERSION").put("platformVersionId",2001).put("productId",1002);assertThrows(ApiException.class,()->service.observe(id,body));
            body.put("productId",1001);assertNotNull(service.observe(id,body).get("id"));
            assertEquals(2,service.observations(id,LocalDate.now()).size());
            var lead=new LinkedHashMap<String,Object>();lead.put("platform",j.queryForObject("SELECT platform FROM platform_version WHERE id=2001",String.class));
            lead.put("externalId",j.queryForObject("SELECT external_id FROM platform_version WHERE id=2001",String.class));lead.put("region","CN");
            service.attachEvidence(List.of(lead),LocalDate.now(),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1));
            assertEquals(1,((List<?>)lead.get("taxonomyEvidence")).size());assertEquals("UNVERIFIED",lead.get("noveltyStatus"));
            service.attachEvidence(List.of(lead),LocalDate.now(),java.time.LocalDateTime.of(2026,1,1,0,0));
            assertTrue(((List<?>)lead.get("taxonomyEvidence")).isEmpty(),"later registered evidence must not leak into historical knowledge");
            lead.put("externalId","another-stable-id");
            service.attachEvidence(List.of(lead),LocalDate.now(),java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1));
            assertTrue(((List<?>)lead.get("taxonomyEvidence")).isEmpty(),"similar names never copy another app's classification");
            assertEquals(products,j.queryForObject("SELECT COUNT(*) FROM product",Long.class));
        }finally{admin.execute("DROP DATABASE "+db);}
    }
}
