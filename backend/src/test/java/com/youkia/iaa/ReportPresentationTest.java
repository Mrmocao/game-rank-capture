package com.youkia.iaa;

import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.service.ReportPresentation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportPresentationTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void projectionPreservesSelectedEvidenceAndDoesNotMutateFrozenReport() throws Exception {
        var leads=mapper.readTree("""
            {"items":[{"name":"SYNTHETIC","history":[{"rank":3,"evidence":{"raw_sha256":"hash"}}],"crossPlatform":{"cards":[{"platform":"IOS","rank":2}]}}],
             "candidateAudit":[{"name":"not selected","selected":false,"exclusionReason":"missing history","entryEvidence":{"firstSeenIn28Days":"2026-11-03","priorCompleteAbsence":[{"raw_sha256":"full"}]},"history":[{"raw_sha256":"full"}]}],
             "productEvidence":{"private-large-copy":"full"}}
            """);
        var snapshot=mapper.createObjectNode();snapshot.set("captureResearchLeads",leads);snapshot.putArray("inputs").add("large-original");
        snapshot.set("result",mapper.readTree("{\"diagnostic\":\"keep\",\"rows\":[{\"name\":\"formal audit\",\"route\":\"paused\",\"dimensions\":[{\"key\":\"risk\"}],\"sourceFacts\":{\"large\":true}}]}"));
        var full=new LinkedHashMap<String,Object>(Map.of("id","23","inputSha256","frozen","researchLeads",leads,"researchSnapshot",snapshot));
        String before=mapper.writeValueAsString(full);var view=ReportPresentation.page(full);
        assertEquals(before,mapper.writeValueAsString(full));assertEquals("frozen",view.get("inputSha256"));
        var presented=(JsonNode)view.get("researchLeads");assertEquals(leads.path("items"),presented.path("items"));
        assertFalse(presented.has("productEvidence"));assertFalse(presented.path("candidateAudit").get(0).has("history"));
        assertEquals("missing history",presented.path("candidateAudit").get(0).path("exclusionReason").asText());
        var shown=(JsonNode)view.get("researchSnapshot");assertFalse(shown.has("inputs"));assertFalse(shown.has("captureResearchLeads"));
        assertEquals(snapshot.path("result").path("rows").get(0).path("dimensions"),shown.path("result").path("rows").get(0).path("dimensions"));
        assertEquals("/api/v1/reports/23",view.get("fullEvidenceUrl"));
    }
    @Test void emptyAndLegacyReportsRemainReadable() throws Exception {
        assertEquals(List.of(),ReportPresentation.page(Map.of("items",List.of())).get("items"));
        var legacy=mapper.readTree("[{\"product_name\":\"legacy\",\"decision\":\"SELECTED\",\"history\":[{}]}]");
        var view=ReportPresentation.page(Map.of("id","1","candidateAudit",legacy));
        assertEquals("legacy",((JsonNode)view.get("candidateAudit")).get(0).path("product_name").asText());assertTrue(legacy.get(0).has("history"));
    }
}
