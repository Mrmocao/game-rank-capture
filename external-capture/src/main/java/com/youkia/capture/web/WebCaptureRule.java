package com.youkia.capture.web;

import com.youkia.capture.CaptureException;
import org.jsoup.Jsoup;
import java.util.*;

/** Declarative schema, intentionally without executable scripts, credentials or business identities. */
public record WebCaptureRule(String url,String itemSelector,List<Field> fields,int minItems,int maxItems,int waitMs,boolean onlyMainContent) {
    public record Field(String name,String selector,String attribute,boolean required) {}
    public WebCaptureRule {
        PublicWebUrl.syntax(url);
        if(itemSelector==null||itemSelector.isBlank()||itemSelector.length()>500||fields==null||fields.isEmpty()||fields.size()>20||minItems<1||maxItems<minItems||maxItems>1000||waitMs<0||waitMs>10000)throw invalid();
        fields=List.copyOf(fields);Set<String> names=new HashSet<>();
        selector(itemSelector);
        for(var f:fields) {
            if(f==null||f.name()==null||!f.name().matches("[a-zA-Z][a-zA-Z0-9_]{0,49}")||!names.add(f.name())||f.selector()==null||f.selector().length()>500||f.attribute()==null||!Set.of("text","href","src","content","datetime","title","alt").contains(f.attribute()))throw invalid();
            if(!f.selector().isBlank())selector(f.selector());
        }
        if(fields.stream().noneMatch(Field::required))throw new CaptureException("RULE_INVALID","至少设置一个必填字段，防止空页面被当作成功");
    }
    private static void selector(String css){
        // Regex selectors can run unbounded backtracking. Keep the online DSL structural.
        if(css.toLowerCase(Locale.ROOT).matches("(?s).*:(matches|contains|has|not).*" )||css.contains("~=")||css.contains("(?"))throw new CaptureException("RULE_SELECTOR_INVALID","不支持正则、文本匹配或嵌套逻辑选择器，请使用结构CSS");
        try{Jsoup.parse("<html><body></body></html>").select(css);}catch(Exception e){throw new CaptureException("RULE_SELECTOR_INVALID","CSS选择器无效");}
    }
    private static CaptureException invalid(){return new CaptureException("RULE_INVALID","请检查URL、记录选择器、字段唯一名称、数量范围1–1000和等待时间0–10000ms");}
}
