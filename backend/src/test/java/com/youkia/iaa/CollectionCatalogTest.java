package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.service.CollectionCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CollectionCatalogTest {
    @Test
    void sourceRegisterKeepsAll42RowsAndPortalTemplate() {
        var service = new CollectionCatalogService(mock(JdbcTemplate.class), new ObjectMapper());
        var all = service.catalog(null, null, null);
        assertEquals(42, all.get("sourceCount"));
        assertEquals(42, ((java.util.List<?>) all.get("items")).size());
        assertEquals(28, all.get("portalFieldCount"));
        var portals = service.catalog(null, null, "WEB_PORTAL");
        assertEquals(12, ((java.util.List<?>) portals.get("items")).size());
        var source = service.source("S31");
        assertEquals("4399小游戏", source.get("name"));
        assertEquals(Boolean.FALSE, source.get("enabled"));
        assertEquals("unknown", source.get("pureIaa"));
        var apple = service.source("S03");
        assertEquals("V3_POC", apple.get("verificationLevel"));
        assertEquals(Boolean.TRUE, apple.get("productionEnabled"));
        assertTrue(((java.util.List<?>) apple.get("runtimeKeys")).contains("APPLE_CN"));
    }

    @Test
    void filtersNeverTurnCatalogRowsIntoSuccess() {
        var service = new CollectionCatalogService(mock(JdbcTemplate.class), new ObjectMapper());
        var rows = (java.util.List<?>) service.catalog("微信", null, null).get("items");
        assertFalse(rows.isEmpty());
        for (Object row : rows) assertEquals(Boolean.FALSE, ((java.util.Map<?, ?>) row).get("enabled"));
        assertTrue(String.valueOf(service.catalog(null, null, null).get("notice")).contains("不会被填充为成功")
                || String.valueOf(service.catalog(null, null, null).get("notice")).contains("尚未执行"));
    }
}
