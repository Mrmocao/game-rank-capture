package com.youkia.iaa;

import com.youkia.iaa.collection.AiExtractionValidator;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiExtractionValidatorTest {
    private final AiExtractionValidator validator = new AiExtractionValidator();
    @Test void rejectsChangedContextAndDuplicateRank() {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("external_id","demo");row.put("name","demo");row.put("rank",1);row.put("monetization_candidate","UNKNOWN");row.put("confidence",null);row.put("evidence",List.of(Map.of("field","rank","locator","rows[0].rank","excerpt","1")));row.put("missing_fields",List.of("monetization_evidence"));
        Map<String,Object> out=new LinkedHashMap<>();out.put("source_id","source");out.put("chart_id","chart");out.put("business_date","2026-10-25");out.put("entries",List.of(row,row));out.put("warnings",List.of());
        assertThrows(RuntimeException.class,()->validator.validate(out,"source","chart",LocalDate.of(2026,10,25),Set.of("rows[0].rank")));
        out.put("entries",List.of(row));out.put("source_id","other");assertThrows(RuntimeException.class,()->validator.validate(out,"source","chart",LocalDate.of(2026,10,25),Set.of("rows[0].rank")));
    }
}
