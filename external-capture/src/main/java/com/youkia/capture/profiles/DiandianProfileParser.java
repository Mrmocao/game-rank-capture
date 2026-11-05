package com.youkia.capture.profiles;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Parses a minimal projection of rendered DOM. No cookies, page state or private APIs. */
public final class DiandianProfileParser {
    private static final Set<String> LABELS=Set.of("开发者","供应商","发布日期","更新日期","Bundle ID","版本","大小","类别","兼容性","支持语言","年龄分级","发行国家/地区","支持网站Url","应用隐私政策Url","支持Watch","家人共享","价格","盈利模式","内购","最新更新","App ID");
    public static boolean detailUrl(URI u) {
        return u!=null && "https".equals(u.getScheme()) && "app.diandian.com".equals(u.getHost())
            && u.getPort()==-1 && u.getUserInfo()==null && u.getPath().matches("/app/[a-zA-Z0-9]+/ios")
            && Arrays.asList(Objects.toString(u.getQuery(), "").split("&")).contains("country=75");
    }
    public static String normalize(String s) {return s==null?"":s.replaceAll("[\\p{P}\\p{Z}\\s]", "").toLowerCase(Locale.ROOT);}
    public static boolean candidate(String seed,String name) {
        seed=seed==null?"":seed.replaceAll("[™®©]","");name=name==null?"":name.replaceAll("[™®©]","");
        String a=normalize(seed),b=normalize(name);
        return !a.isEmpty() && (a.equals(b)||name.matches(Pattern.quote(seed)+"\\s*[-—:：·].+"));
    }
    public ProfileDocument parse(RawMaterial raw) {
        if(!detailUrl(raw.sourceUrl()))throw new CaptureException("PROFILE_SOURCE_MISMATCH","仅支持点点中国iOS详情材料");
        try {
            JsonNode d=new ObjectMapper().readTree(raw.text());
            if(!d.path("format").asText().equals("diandian-visible-v1"))throw new IllegalArgumentException();
            String name=d.path("name").asText().strip(),head=d.path("header").asText();
            var sections=d.path("sections");String base=sections.path("基本信息").asText();
            String id=next(head,"App ID"),developer=next(base,"开发者");
            if(name.isBlank()||name.length()>500||developer.isBlank()||!id.matches("[0-9]{5,20}"))
                throw new CaptureException("PROFILE_FIELDS_MISSING","详情缺少名称、App ID或开发者，登录页不能作为资料");
            boolean identity=false;
            for(var link:d.path("storeLinks")) {
                var u=URI.create(link.asText());
                if("https".equals(u.getScheme())&&"apps.apple.com".equals(u.getHost())&&u.getPath().startsWith("/cn/app/")&&u.getPath().endsWith("/id"+id))identity=true;
            }
            if(!identity)throw new CaptureException("PROFILE_ID_MISMATCH","可见Apple链接与App ID不一致");
            var f=new LinkedHashMap<String,String>();
            for(String key:List.of("供应商","发布日期","更新日期","Bundle ID","版本","大小","类别","兼容性","支持语言","年龄分级","发行国家/地区","支持网站Url","应用隐私政策Url"))put(f,key,next(base,key));
            for(String key:List.of("价格","盈利模式","内购","最新更新"))put(f,key,next(head,key));
            var rating=Pattern.compile("(?m)^([^\\n]*个评分)\\s*\\n([0-5](?:\\.[0-9]+)?)\\s*$").matcher(head);
            if(rating.find()){put(f,"评分数量",rating.group(1));put(f,"评分",rating.group(2));}
            var filing=Pattern.compile("(?m)^(备案号|游戏版号)[：:](.+)$").matcher(head);
            while(filing.find())put(f,filing.group(1),filing.group(2));
            for(String key:List.of("应用描述","热门分类","热门内购","版本记录","新内容"))put(f,key,sections.path(key).asText());
            // A free global preview can concern a collection, and both period buttons may
            // be visible. Preserve the original section rather than inventing its scope.
            put(f,"全球速览原文",sections.path("全球速览").asText());
            return new ProfileDocument("diandian-browser","IOS","cn",id,name,developer,Map.copyOf(f),raw.sourceUrl(),raw.capturedAt(),raw.sha256(),List.of(
                "点点登录账号可见资料；会员隐藏字段不采集。",
                "全球速览可能属于跨国合集；周期及应用/合集选择未核验，仅保留展示原文，不作为中国日下载或收入数值。",
                "与微信/抖音同名仅为候选资料，跨平台身份未核验；售价和内购项目不代表流水或付费人数。"));
        }catch(CaptureException e){throw e;}catch(Exception e){throw new CaptureException("PROFILE_PARSE_FAILED","点点可见资料材料格式不正确");}
    }
    private static void put(Map<String,String> f,String k,String v){if(v!=null&&!v.isBlank()&&!v.matches("[xX*\\s]+"))f.put(k,v.strip().substring(0,Math.min(v.strip().length(),30000)));}
    private static String next(String text,String label){var lines=text.lines().map(String::strip).filter(s->!s.isBlank()).toList();for(int i=0;i+1<lines.size();i++)if(lines.get(i).equals(label)){String value=lines.get(i+1);return LABELS.contains(value)||value.equals("-")?"":value;}return "";}
}
