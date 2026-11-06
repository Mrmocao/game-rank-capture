package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.collection.AppleGamesService;
import com.youkia.iaa.service.WeeklyReportService;
import com.youkia.iaa.service.ProductQueryService;
import com.youkia.iaa.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses real MySQL but rolls back all synthetic changes. Source sample remains a real captured file. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_MYSQL_TESTS", matches="true")
class LiveMySqlPipelineTest {
    @TempDir Path materials;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private record Context(JdbcTemplate jdbc, TransactionTemplate tx) {}
    private Context context() throws Exception {
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(Path.of("../.env"))){int p=line.indexOf('=');if(p>0&&!line.startsWith("#"))env.put(line.substring(0,p),line.substring(p+1));}
        var ds=new DriverManagerDataSource(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"));
        return new Context(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }
    @Test void partialIdempotencyRevisionAndFrozenReport() throws Exception {
        var ctx=context();String original=Files.readString(Path.of("../validation/apple-games-cn.json"));
        var tree=mapper.readTree(original);var entries=(com.fasterxml.jackson.databind.node.ArrayNode)tree.path("feed").path("entry");
        while(entries.size()>40)entries.remove(entries.size()-1);
        String partial=mapper.writeValueAsString(tree);
        var service=new AppleGamesService(ctx.jdbc,mapper,materials.toString());
        var reports=new WeeklyReportService(ctx.jdbc,mapper);
        ctx.tx.executeWithoutResult(tx->{
            try {
                long before=ctx.jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot",Long.class);
                var first=service.ingest("cn",100,null,partial);long snapshot=Long.parseLong(first.get("snapshotId").toString());
                assertEquals("PARTIAL",first.get("completeness"));assertEquals(40,first.get("entries"));
                assertEquals(40,ctx.jdbc.queryForObject("SELECT COUNT(*) FROM ranking_entry WHERE snapshot_id=?",Integer.class,snapshot));
                assertEquals(true,service.ingest("cn",100,null,partial).get("reused"));
                assertEquals(before+1,ctx.jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot",Long.class));
                LocalDate date=(LocalDate)first.get("businessDate");var report=reports.generate(date.minusDays(6),date.plusDays(1));
                assertTrue(((List<?>)report.get("items")).size()<=5);
                assertNotNull(report.get("skillSnapshot"));assertNotNull(report.get("candidateAudit"));
                var repeat=reports.generate(date.minusDays(6),date.plusDays(1));
                assertEquals(report.get("id"),repeat.get("id"));assertEquals(true,repeat.get("reused"));
                long id=Long.parseLong(report.get("id").toString());String frozen=mapper.writeValueAsString(reports.report(id).get("items"));
                // Source revisions stay immutable; explicit regeneration replaces the same weekly report.
                var revised=service.ingest("cn",100,null,partial+"\n");assertNotEquals(first.get("snapshotId"),revised.get("snapshotId"));
                assertEquals(frozen,mapper.writeValueAsString(reports.report(id).get("items")));
                var next=reports.generate(date.minusDays(6),date.plusDays(1));assertEquals(report.get("id"),next.get("id"));
                assertEquals(((Number)report.get("revision")).intValue()+1,((Number)next.get("revision")).intValue());
                assertEquals(frozen,mapper.writeValueAsString(reports.report(id).get("items")));
                assertTrue(((List<?>)reports.history(1,100).get("items")).size()>1);
                var previousPeriod=reports.generate(date.minusDays(7),date);
                assertTrue(((List<?>)previousPeriod.get("items")).isEmpty(),"end is exclusive; never backfill a previous period with today");
                assertEquals(0L,report.get("domesticCount"));assertEquals(0L,report.get("overseasCount"));
                assertTrue(((List<Map<String,Object>>)report.get("items")).stream().allMatch(i->"OBSERVATION".equals(i.get("section"))));
            }catch(Exception e){throw new RuntimeException(e);}finally{tx.setRollbackOnly();}
        });
    }
    @Test void badMaterialDoesNotCreateSuccessSnapshot() throws Exception {
        var ctx=context();var service=new AppleGamesService(ctx.jdbc,mapper,materials.toString());
        long snapshots=ctx.jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot",Long.class);
        assertThrows(ApiException.class,()->service.ingest("cn",100,null,"{\"feed\":{\"entry\":[]}}"));
        assertThrows(ApiException.class,()->service.ingest("cn",100,null,"not JSON"));
        assertEquals(snapshots,ctx.jdbc.queryForObject("SELECT COUNT(*) FROM ranking_snapshot",Long.class));
    }
    @Test void realRankingsAndMissingDateNeverBackfill() throws Exception {
        var ctx=context();var query=new ProductQueryService(ctx.jdbc);
        long chart=ctx.jdbc.queryForObject("SELECT c.id FROM chart_definition c JOIN data_source s ON s.id=c.source_id WHERE s.adapter_key='apple-games-public' AND c.region='CN'",Long.class);
        String date=ctx.jdbc.queryForObject("SELECT MAX(business_date) FROM ranking_snapshot WHERE chart_id=?",String.class,chart);
        var ranks=query.rankings(chart,date);assertEquals(100,ranks.size());assertEquals(1,((Number)ranks.getFirst().get("rank")).intValue());
        assertTrue(query.rankings(chart,"2000-01-01").isEmpty());
        assertEquals(0,query.list("2000-01-01",null,null,null,null,null,null,1,20).get("total"));
        long product=((Number)ranks.getFirst().get("product_id")).longValue();
        assertTrue(query.rankingRows(product,LocalDate.parse(date)).stream().filter(r->String.valueOf(chart).equals(r.get("chartId"))).allMatch(r->r.get("delta7d")==null));
    }
    @Test void regionFilterRequiresAnObservedRegionInsteadOfOnlyAChartDefinition() throws Exception {
        var ctx=context();var query=new ProductQueryService(ctx.jdbc);
        String date=String.valueOf(query.latestAvailableDate());
        var items=(List<Map<String,Object>>)query.list(date,null,"IOS","US",null,null,null,1,20).get("items");
        assertFalse(items.isEmpty());
        for(var item:items){
            long count=ctx.jdbc.queryForObject("SELECT COUNT(*) FROM platform_version v JOIN ranking_entry e ON e.platform_version_id=v.id JOIN ranking_snapshot s ON s.id=e.snapshot_id JOIN chart_definition c ON c.id=s.chart_id WHERE v.product_id=? AND v.platform='IOS' AND c.region='US' AND s.business_date<=? AND s.status='PUBLISHED'",Long.class,item.get("id"),date);
            assertTrue(count>0,"country filtering requires actual source observations");
        }
    }
}
