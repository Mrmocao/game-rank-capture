package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Read-only page projection. Full evidence remains in the frozen report and the default API. */
public final class ReportPresentation {
    private ReportPresentation() {}
    public static Map<String,Object> page(Map<String,Object> full) {
        var out=new LinkedHashMap<>(full);
        if(full.get("researchLeads") instanceof JsonNode leads){
            var view=without(leads,"candidateAudit","productEvidence");
            view.set("candidateAudit",rows(leads.path("candidateAudit"),"name","boardName","selected","entryEvidence","opportunityAssessment","exclusionReason"));
            // Only firstSeenIn28Days is shown for unselected candidates; selected items retain full evidence.
            for(var row:view.path("candidateAudit"))if(row instanceof ObjectNode object&&object.has("entryEvidence"))
                object.set("entryEvidence",pick(object.path("entryEvidence"),"firstSeenIn28Days"));
            out.put("researchLeads",view);
        }
        if(full.get("researchSnapshot") instanceof JsonNode snapshot){
            var view=without(snapshot,"inputs","captureResearchLeads","health");
            var result=without(snapshot.path("result"),"rows","selected");
            result.set("rows",rows(snapshot.path("result").path("rows"),"id","productId","name","route","utilityRange","inputCoverage","action","unselectedReason","slot","evidenceTasks","dimensions"));
            view.set("result",result);out.put("researchSnapshot",view);
            // This duplicates the result rows and is not rendered when researchSnapshot exists.
            out.put("candidateAudit",JsonNodeFactory.instance.arrayNode());
        }else if(full.get("candidateAudit") instanceof JsonNode audit){
            var rows=rows(audit,"product_name","platform","region","decision","v22");
            for(var row:rows)if(row instanceof ObjectNode object&&object.has("v22"))
                object.set("v22",pick(object.path("v22"),"utilityRange","unselectedReason","action"));
            out.put("candidateAudit",rows);
        }
        out.put("presentation","PAGE");
        if(full.get("id")!=null)out.put("fullEvidenceUrl","/api/v1/reports/"+full.get("id"));
        return out;
    }
    private static ObjectNode without(JsonNode source,String... excluded){
        var out=JsonNodeFactory.instance.objectNode();Set<String> omit=Set.of(excluded);
        source.fields().forEachRemaining(e->{if(!omit.contains(e.getKey()))out.set(e.getKey(),e.getValue().deepCopy());});return out;
    }
    private static ObjectNode pick(JsonNode source,String... fields){
        var out=JsonNodeFactory.instance.objectNode();for(String key:fields)if(source.has(key))out.set(key,source.get(key).deepCopy());return out;
    }
    private static ArrayNode rows(JsonNode source,String... fields){
        var out=JsonNodeFactory.instance.arrayNode();if(source.isArray())source.forEach(row->out.add(pick(row,fields)));return out;
    }
}
