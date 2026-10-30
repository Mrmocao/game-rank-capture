package com.youkia.iaa.collection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class InsightCollectionTest {
    public static java.sql.Timestamp utcTimestamp(int precision){return java.sql.Timestamp.from(java.time.Instant.now());}
    @Test void failureIsRecordedAndRestartDoesNotInventSuccess(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:attempts;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.youkia.iaa.collection.InsightCollectionTest.utcTimestamp'");
        jdbc.execute("CREATE TABLE insight_collection_attempt(id BIGINT AUTO_INCREMENT PRIMARY KEY,source_key VARCHAR(80),requested_date DATE,status VARCHAR(24),result_json VARCHAR(4096),started_at TIMESTAMP,finished_at TIMESTAMP)");
        var service=new InsightCollectionService(jdbc,new ObjectMapper(),null,null,null,false,false,false);
        var failure=service.attempt("TEST_SOURCE",()->{throw new java.net.http.HttpTimeoutException("test");});
        assertEquals("FAILED",failure.get("status"));assertTrue(String.valueOf(failure.get("result")).contains("SOURCE_TIMEOUT"));
        var success=service.attempt("TEST_SOURCE",()->Map.of("snapshotId","verified-test-id","reused",true));assertEquals("SUCCESS",success.get("status"));
        jdbc.update("INSERT INTO insight_collection_attempt(source_key,status) VALUES('TEST_INTERRUPTED','RUNNING')");service.recoverInterrupted();
        assertEquals("INTERRUPTED",jdbc.queryForObject("SELECT status FROM insight_collection_attempt WHERE source_key='TEST_INTERRUPTED'",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM insight_collection_attempt WHERE status='SUCCESS'",Integer.class));
    }
}
