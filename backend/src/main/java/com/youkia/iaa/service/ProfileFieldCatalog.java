package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.ClassPathResource;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;

/** Controlled projection of the 120-indicator catalog; never accepts arbitrary field keys. */
public final class ProfileFieldCatalog {
    private final ObjectMapper mapper;
    private final JsonNode document;
    private final Map<String,JsonNode> definitions=new LinkedHashMap<>();
    public ProfileFieldCatalog(ObjectMapper mapper) {
        this.mapper=mapper;
        try(var in=new ClassPathResource("profile/fields.json").getInputStream()) { document=mapper.readTree(in); }
        catch(Exception e){throw new IllegalStateException("档案字段定义无法加载",e);}
        document.path("fields").forEach(f->definitions.put(f.path("key").asText(),f));
        if(definitions.size()!=81)throw new IllegalStateException("档案必须有81项定义");
    }
    public JsonNode document(){return document.deepCopy();}
    public Collection<JsonNode> fields(){return definitions.values();}
    public JsonNode field(String key){var f=definitions.get(key);if(f==null)throw new IllegalArgumentException("未知档案字段："+key);return f;}
    public boolean sensitive(String key){return field(key).path("versionSensitive").asBoolean();}
    public void validate(String key,JsonNode value,String status){
        var f=field(key);
        if(!Set.of("available","missing","not_authorized","not_supported","stale","conflict","not_applicable").contains(status))throw new IllegalArgumentException("未知字段状态");
        if(!status.equals("available")){if(value!=null&&!value.isNull())throw new IllegalArgumentException("非可用字段必须为null");return;}
        if(value==null||value.isNull())throw new IllegalArgumentException("有资料字段必须有类型值");
        boolean valid=switch(f.path("type").asText()){
            case "number"->value.isNumber()&&Double.isFinite(value.asDouble())&&value.asDouble()>=0;
            case "list"->value.isArray()&&value.size()<=100&&java.util.stream.StreamSupport.stream(value.spliterator(),false).allMatch(x->x.isTextual()&&!x.asText().isBlank()&&x.asText().length()<=2000);
            case "range"->value.isObject()&&value.path("lower").isNumber()&&value.path("upper").isNumber()&&value.path("lower").asDouble()>=0&&value.path("upper").asDouble()>=value.path("lower").asDouble();
            case "object"->value.isObject();
            case "enum"->value.isTextual()&&Set.of("IAA","mixed","IAP","unknown").contains(value.asText());
            case "date"->{try{LocalDate.parse(value.asText());yield value.isTextual();}catch(Exception e){yield false;}}
            case "reference"->{try{safeUrl(value.asText());yield value.isTextual();}catch(Exception e){yield false;}}
            default->value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=10000;
        };
        if(!valid)throw new IllegalArgumentException("字段类型或范围无效："+key+"（"+f.path("type").asText()+"）");
        if(Set.of("rank","peak").contains(key)&&(!value.isIntegralNumber()||value.asInt()<1))throw new IllegalArgumentException("名次必须为正整数，缺失不能填0");
        if(Set.of("rating_count","review_count","observed_days","mentions","creators").contains(key)&&!value.isIntegralNumber())throw new IllegalArgumentException("计数必须为整数");
        if(key.equals("sample")){
            if(!value.path("count").isIntegralNumber()||value.path("count").asLong()<0||value.path("method").asText().isBlank()||value.path("language").asText().isBlank())throw new IllegalArgumentException("评价样本需明确count、method、language，不能借用评分数");
            try{if(LocalDate.parse(value.path("from").asText()).isAfter(LocalDate.parse(value.path("to").asText())))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("评价样本需有效的from/to观察窗口");}
        }
    }
    public static String safeUrl(String value){
        if(value==null||value.isBlank())return null;
        try{var u=URI.create(value);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||value.length()>2000)throw new IllegalArgumentException();return value;}
        catch(Exception e){throw new IllegalArgumentException("来源链接仅支持不含凭据的http/https网址");}
    }
}
