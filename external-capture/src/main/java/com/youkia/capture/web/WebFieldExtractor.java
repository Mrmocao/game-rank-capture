package com.youkia.capture.web;

import com.youkia.capture.*;
import org.jsoup.Jsoup;
import java.util.*;

/** Source text and field locators only; never infers rank, identity, date or commercial facts. */
public final class WebFieldExtractor {
    public record Row(int position,Map<String,String> fields,Map<String,String> evidence){}
    public record Result(String status,int actualCount,int matchedCount,List<Row> rows,List<String> issues){}
    public Result extract(WebCaptureRule rule,RawMaterial raw) {
        var doc=Jsoup.parse(raw.text(),rule.url());doc.select("script,style,form,input,textarea,noscript").remove();
        var matches=doc.select(rule.itemSelector());var rows=new ArrayList<Row>();var issues=new ArrayList<String>();
        int count=Math.min(matches.size(),rule.maxItems());
        if(matches.size()<rule.minItems())issues.add("匹配记录少于最低要求："+matches.size()+" / "+rule.minItems());
        if(matches.size()>rule.maxItems())issues.add("匹配记录超过上限，仅保存前"+rule.maxItems()+"条；请缩小选择范围或调整上限");
        int outputChars=0;
        for(int i=0;i<count;i++) {
            var item=matches.get(i);var values=new LinkedHashMap<String,String>();var evidence=new LinkedHashMap<String,String>();
            for(var field:rule.fields()) {
                var selected=field.selector().isBlank()?List.of(item):item.select(field.selector());
                String value="";
                if(selected.size()>1){if(issues.size()<50)issues.add("第"+(i+1)+"条字段"+field.name()+"匹配多个元素");}
                else if(selected.size()==1){var element=selected.getFirst();value=field.attribute().equals("text")?element.text():element.attr(field.attribute());evidence.put(field.name(),element.cssSelector());}
                if(value.length()>4000){value=value.substring(0,4000);if(issues.size()<50)issues.add("字段过长已截断："+field.name());}
                if(field.required()&&value.isBlank()&&issues.size()<50)issues.add("第"+(i+1)+"条缺少必填字段："+field.name());
                values.put(field.name(),value);
            }
            int rowChars=values.values().stream().mapToInt(String::length).sum()+evidence.values().stream().mapToInt(String::length).sum();
            if(outputChars+rowChars>1_000_000){issues.add("提取结果超过100万字符上限，请减少字段或缩小记录范围");break;}
            outputChars+=rowChars;
            rows.add(new Row(i+1,values,evidence));
        }
        return new Result(issues.isEmpty()?"SUCCEEDED":rows.isEmpty()?"FAILED":"PARTIAL",rows.size(),matches.size(),List.copyOf(rows),List.copyOf(issues));
    }
}
