package com.youkia.iaa.collection;

import com.youkia.iaa.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Boundary validator for the versioned AI extraction contract. It deliberately
 * treats AI output as a candidate: no monetization conclusion is admitted here.
 */
@Component
public class AiExtractionValidator {
    public void validate(Map<String, Object> output, String expectedSourceId, String expectedChartId, LocalDate expectedDate, Set<String> allowedLocators) {
        require(output, "source_id"); require(output, "chart_id"); require(output, "business_date"); require(output, "entries"); require(output, "warnings");
        if (!Objects.equals(expectedSourceId, output.get("source_id")) || !Objects.equals(expectedChartId, output.get("chart_id"))) reject("AI上下文source_id/chart_id不可改写");
        if (!Objects.equals(expectedDate.toString(), output.get("business_date"))) reject("AI业务日期不可改写");
        Object entries=output.get("entries"); if (!(entries instanceof List<?>)) { reject("entries必须是数组"); return; } List<?> list=(List<?>) entries;
        Set<Object> ids=new HashSet<>(), ranks=new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?,?>)) { reject("entry必须是对象"); continue; } Map<?,?> row=(Map<?,?>) item;
            require(row,"external_id"); require(row,"name"); require(row,"rank"); require(row,"monetization_candidate"); require(row,"confidence"); require(row,"evidence"); require(row,"missing_fields");
            Object rank=row.get("rank"); if (rank!=null && (!(rank instanceof Number n) || n.intValue()<1 || !ranks.add(n.intValue()))) reject("排名必须为正整数且不得重复");
            Object id=row.get("external_id"); if (id!=null && !ids.add(id)) reject("external_id不得重复");
            Object mode=row.get("monetization_candidate"); if (!Set.of("UNKNOWN","PURE_IAA","MIXED","IAP","OTHER").contains(mode)) reject("不支持的变现候选值");
            Object confidence=row.get("confidence"); if (confidence!=null && (!(confidence instanceof Number n) || n.doubleValue()<0 || n.doubleValue()>1)) reject("confidence必须在0到1之间");
            if (row.get("evidence") instanceof List<?> evidence) for (Object ev:evidence) if (ev instanceof Map<?,?> e) { require(e,"field"); require(e,"locator"); require(e,"excerpt"); if (!allowedLocators.isEmpty() && !allowedLocators.contains(String.valueOf(e.get("locator")))) reject("evidence locator不在原始材料范围"); } else reject("evidence必须是对象数组"); else reject("evidence必须是数组");
        }
    }
    private void require(Map<?,?> map,String key){if(!map.containsKey(key))reject("缺少字段: "+key);}
    private void reject(String message){throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"AI_OUTPUT_INVALID",message);}
}
