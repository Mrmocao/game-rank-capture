package com.youkia.capture.metrics;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic source facts, never forecasts or cross-platform attribution. */
public final class StoreMetricParser {
    private final ObjectMapper mapper;
    public StoreMetricParser(ObjectMapper mapper) { this.mapper=mapper; }
    public GameMetric.Snapshot parse(MetricTarget target, RawMaterial material) {
        if (!target.url().equals(material.sourceUrl())) throw new CaptureException("METRIC_SOURCE_MISMATCH","指标材料与请求来源不一致");
        try {
            var metrics=new ArrayList<GameMetric.Value>();var purchases=new ArrayList<GameMetric.Purchase>();
            var limitations=new ArrayList<String>();String name;
            if(target.source().equals("apple-lookup")) {
                var root=mapper.readTree(material.text());var results=root.path("results");
                if(root.path("resultCount").asInt()!=1||results.size()!=1)throw new CaptureException("STORE_APP_NOT_FOUND","该地区商店未返回唯一应用");
                var app=results.get(0);
                if(!app.path("trackId").asText().equals(target.externalId())||!app.path("primaryGenreId").asText().equals("6014"))throw new CaptureException("STORE_ID_MISMATCH","应用ID或游戏分类不匹配");
                name=app.path("trackName").asText();
                number(metrics,"price",app.get("price"),app.path("formattedPrice").asText(),"money",app.path("currency").asText(),target.region(),"results[0].price");
                number(metrics,"rating",app.get("averageUserRating"),"","score","",target.region(),"results[0].averageUserRating");
                number(metrics,"rating_count",app.get("userRatingCount"),"","count","",target.region(),"results[0].userRatingCount");
                limitations.add("官方公开接口不提供竞品下载量、收入、付费人数；售价不是收入。");
            } else {
                var doc=Jsoup.parse(material.text(),target.url().toString());
                String canonical=doc.select("link[rel=canonical]").attr("href");
                URI uri=URI.create(canonical);
                if(target.platform().equals("IOS")) {
                    if(!"apps.apple.com".equals(uri.getHost())||!uri.getPath().startsWith("/"+target.region()+"/app/")||!uri.getPath().endsWith("/id"+target.externalId()))throw new CaptureException("STORE_ID_MISMATCH","Apple详情地区或ID不一致");
                } else if(!"play.google.com".equals(uri.getHost())||!target.externalId().equals(query(uri,"id")))throw new CaptureException("STORE_ID_MISMATCH","Google详情包名不一致");
                JsonNode app=null;
                for(var script:doc.select("script[type=application/ld+json]")) {
                    var node=mapper.readTree(script.data());
                    if(node.path("@type").asText().equals("SoftwareApplication")){app=node;break;}
                }
                if(app==null||app.path("name").asText().isBlank())throw new CaptureException("STORE_STRUCTURE_CHANGED","未找到有效商店应用结构，登录/拦截页不算成功");
                name=app.path("name").asText();
                var offers=app.path("offers");if(offers.isArray())offers=offers.path(0);
                number(metrics,"price",offers.get("price"),"","money",offers.path("priceCurrency").asText(),target.region(),"SoftwareApplication.offers.price");
                number(metrics,"rating",app.path("aggregateRating").get("ratingValue"),"","score","",target.region(),"SoftwareApplication.aggregateRating.ratingValue");
                var rating=app.path("aggregateRating");number(metrics,"rating_count",rating.has("ratingCount")?rating.get("ratingCount"):rating.get("reviewCount"),"","count","",target.region(),"SoftwareApplication.aggregateRating.ratingCount/reviewCount");
                if(target.platform().equals("IOS")) {
                    for(var dt:doc.select("dt"))if(Set.of("App内购买","App 內購買","In-App Purchases","Achats intégrés","In-App-Käufe").contains(dt.text().strip())) {
                        Element dd=dt.nextElementSibling();if(dd==null)continue;
                        String flag=dd.select("summary").text();
                        if(Set.of("是","Yes","Oui","Ja").contains(flag))metrics.add(flag("has_iap","是",target.region(),dt.cssSelector()));
                        for(var pair:dd.select(".text-pair")) {
                            var spans=pair.children();if(spans.size()!=2||purchases.size()>=100)continue;
                            purchases.add(new GameMetric.Purchase(bounded(spans.get(0).text()),bounded(spans.get(1).text()),pair.cssSelector()));
                        }
                    }
                    limitations.add("内购项目仅为商店页面展示部分，不保证完整；未出现标签保持未知。");
                } else {
                    for(var el:doc.select("div,span")) {
                        if(el.ownText().equals("Downloads")&&el.parent()!=null) {
                            var siblings=el.parent().children();if(siblings.size()==2&&siblings.get(1)==el) {
                                String display=siblings.get(0).text();
                                if(display.matches("[0-9.,]+\\s*[KMB]?\\+"))metrics.add(new GameMetric.Value("installs",display,null,"installs","","STORE_RANGE","GLOBAL","LIFETIME",el.parent().cssSelector()));
                            }
                        }
                    }
                    // Only labels near the app heading; descriptions/reviews cannot establish these flags.
                    var title=doc.selectFirst("h1");Element header=title;
                    for(int i=0;i<3&&header!=null;i++,header=header.parent()) {
                        if(header.select("span,div").stream().anyMatch(e->e.ownText().equals("In-app purchases"))) {metrics.add(flag("has_iap","是",target.region(),header.cssSelector()+" [In-app purchases]"));break;}
                    }
                    header=title;
                    for(int i=0;i<3&&header!=null;i++,header=header.parent())if(header.select("span,div").stream().anyMatch(e->e.ownText().equals("Contains ads"))){metrics.add(flag("contains_ads","是",target.region(),header.cssSelector()+" [Contains ads]"));break;}
                    limitations.add("下载量为全球累计档位，不是所选国家日下载；广告/内购标签不表示具体收入。");
                }
            }
            if(metrics.isEmpty()&&purchases.isEmpty())throw new CaptureException("METRIC_EMPTY","没有取得可核实指标");
            return new GameMetric.Snapshot(target,bounded(name),"store-metrics-v1",target.url().toString(),material.capturedAt(),material.sha256(),List.copyOf(metrics),List.copyOf(purchases),List.copyOf(limitations));
        }catch(CaptureException e){throw e;}catch(Exception e){throw new CaptureException("METRIC_PARSE_FAILED","商店指标材料无法解析");}
    }
    private static String query(URI uri,String key){if(uri.getRawQuery()==null)return null;for(String p:uri.getRawQuery().split("&")){String[] v=p.split("=",2);if(v.length==2&&v[0].equals(key))return URLDecoder.decode(v[1],StandardCharsets.UTF_8);}return null;}
    private static String bounded(String s){return s.substring(0,Math.min(s.length(),1000));}
    private static GameMetric.Value flag(String key,String display,String region,String evidence){return new GameMetric.Value(key,display,BigDecimal.ONE,"boolean","","OFFICIAL_DISPLAY",region,"AT_CAPTURE",evidence);}
    private static void number(List<GameMetric.Value> out,String key,JsonNode node,String display,String unit,String currency,String region,String evidence) {
        if(node==null||node.isNull()||!node.asText().matches("[0-9]+(?:\\.[0-9]+)?"))return;
        BigDecimal n=new BigDecimal(node.asText());if(n.signum()<0||n.precision()>20||key.equals("rating")&&n.compareTo(BigDecimal.valueOf(5))>0)return;
        if(key.equals("price")&&!currency.matches("[A-Z]{3}"))return;
        out.add(new GameMetric.Value(key,display.isBlank()?node.asText():display,n,unit,currency,"OFFICIAL_DISPLAY",region,"AT_CAPTURE",evidence));
    }
}
