package com.youkia.capture.web;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

/** Single-page bounded transport. No crawl fan-out, credentials on target requests, or model extraction. */
public class WebTransport {
    public record Provider(String baseUrl,String apiKey,boolean enabled,int revision) {
        @Override public String toString(){return "Provider[redacted]";}
    }
    private final ObjectMapper mapper;private final Clock clock;private final String trustedBase;
    public WebTransport(ObjectMapper mapper,Clock clock,String trustedBase){this.mapper=mapper;this.clock=clock;this.trustedBase=trustedBase==null?"":trustedBase.replaceAll("/+$","");}
    public String providerUrl(String value) {
        String base=value==null?"":value.strip().replaceAll("/+$","");
        if(!trustedBase.isEmpty()&&base.equals(trustedBase))return base;
        URI uri=PublicWebUrl.check(base);
        if(uri.getQuery()!=null)throw new CaptureException("PROVIDER_URL_INVALID","服务地址不得包含查询参数");
        return base;
    }
    public RawMaterial fetch(String tool,WebCaptureRule rule,Provider provider) {
        URI target=PublicWebUrl.check(rule.url());
        if(tool.equals("web-http")) {
            return direct(target,false);
        }
        if(!tool.equals("firecrawl"))throw new CaptureException("WEB_TOOL_UNSUPPORTED","未知网页抓取工具");
        if(provider==null||!provider.enabled())throw new CaptureException("FIRECRAWL_NOT_CONFIGURED","请先配置并启用Firecrawl服务");
        String base=providerUrl(provider.baseUrl());
        var payload=Map.of("url",rule.url(),"formats",List.of("rawHtml"),"onlyMainContent",rule.onlyMainContent(),"waitFor",rule.waitMs(),"timeout",60000,"maxAge",0,"storeInCache",false);
        try {
            var builder=HttpRequest.newBuilder(URI.create(base+"/scrape")).timeout(Duration.ofSeconds(75)).header("Content-Type","application/json");
            if(!provider.apiKey().isBlank())builder.header("Authorization","Bearer "+provider.apiKey());
            var response=send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload))).build(),80);
            if(response.statusCode()!=200)throw new CaptureException(switch(response.statusCode()){case 401,403->"FIRECRAWL_AUTH_REQUIRED";case 402->"FIRECRAWL_CREDITS_REQUIRED";case 408,504->"FIRECRAWL_TIMEOUT";case 429->"FIRECRAWL_RATE_LIMIT";default->"FIRECRAWL_HTTP_FAILED";},"Firecrawl请求未成功，请检查服务状态、凭据或额度");
            var body=mapper.readTree(response.body());
            if(!body.path("success").asBoolean(false))throw new CaptureException("FIRECRAWL_SCRAPE_FAILED","Firecrawl未能取得页面；响应错误不作为网页数据保存");
            var data=body.path("data");var metadata=data.path("metadata");int sourceStatus=metadata.path("statusCode").asInt(0);
            if(sourceStatus<200||sourceStatus>=300)throw new CaptureException("SOURCE_HTTP_FAILED","源站响应状态缺失或非成功，不能标记抓取成功");
            String finalUrl=metadata.path("url").asText(rule.url());URI finalUri=PublicWebUrl.check(finalUrl);
            if(!finalUri.getHost().equalsIgnoreCase(target.getHost()))throw new CaptureException("SOURCE_REDIRECTED","页面跳转到其他域名，请核验并更新目标URL");
            String html=data.path("rawHtml").asText("");if(html.isBlank())throw new CaptureException("FIRECRAWL_EMPTY_HTML","Firecrawl没有返回原始HTML");
            return new RawMaterial(target,clock.instant(),"firecrawl-v2-rawHtml",html.getBytes(StandardCharsets.UTF_8));
        }catch(CaptureException e){throw e;}catch(Exception e){throw new CaptureException("FIRECRAWL_RESPONSE_INVALID","Firecrawl响应格式无法解析");}
    }
    public RawMaterial fetchJson(URI target) { return direct(PublicWebUrl.check(target.toString()),true); }
    public RawMaterial fetchStoreHtml(URI target) {
        PublicWebUrl.check(target.toString());
        if(!"apps.apple.com".equals(target.getHost())||!target.getPath().matches("/(cn|us|gb|de|fr)/app/id[0-9]{5,20}"))throw new CaptureException("METRIC_SCOPE_INVALID","仅允许固定Apple商店详情");
        return direct(target,false,true);
    }
    private RawMaterial direct(URI target,boolean json) {return direct(target,json,false);}
    private RawMaterial direct(URI target,boolean json,boolean storeRedirect) {
        // Resolve once and pin the validated addresses into the actual TLS connection.
        // Hostname verification still uses the original hostname; no DNS rebind to private services.
        try {
            InetAddress[] addresses=InetAddress.getAllByName(target.getHost());
            for(var address:addresses)if(!PublicWebUrl.publicAddress(address))throw new CaptureException("PRIVATE_ADDRESS_BLOCKED","不允许连接私网或保留地址");
            org.apache.hc.client5.http.DnsResolver resolver=new org.apache.hc.client5.http.DnsResolver(){
                public InetAddress[] resolve(String host)throws UnknownHostException{if(!host.equalsIgnoreCase(target.getHost()))throw new UnknownHostException();return addresses.clone();}
                public String resolveCanonicalHostname(String host)throws UnknownHostException{if(!host.equalsIgnoreCase(target.getHost()))throw new UnknownHostException();return host;}
            };
            var manager=org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom().setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(10)).setSocketTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(20)).build()).build();
            var builder=org.apache.hc.client5.http.impl.classic.HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().disableCookieManagement();
            if(!storeRedirect)builder.disableRedirectHandling();else builder.setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom().setMaxRedirects(2).build()).setRedirectStrategy(new org.apache.hc.client5.http.impl.DefaultRedirectStrategy(){
                @Override public URI getLocationURI(org.apache.hc.core5.http.HttpRequest request,org.apache.hc.core5.http.HttpResponse response,org.apache.hc.core5.http.protocol.HttpContext context)throws org.apache.hc.core5.http.HttpException {
                    URI location=super.getLocationURI(request,response,context);PublicWebUrl.check(location.toString());
                    String id=target.getPath().substring(target.getPath().lastIndexOf('/'));
                    if(!target.getHost().equals(location.getHost())||!location.getPath().startsWith(target.getPath().substring(0,8))||!location.getPath().endsWith(id))throw new CaptureException("STORE_REDIRECT_MISMATCH","商店跳转身份或地区不一致");
                    return location;
                }
            });
            try(var client=builder.build();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var request=new org.apache.hc.client5.http.classic.methods.HttpGet(target);request.setHeader("User-Agent","YoukiaCapture/1.0");request.setHeader("Accept",json?"application/json":"text/html,application/xhtml+xml");
                var future=executor.submit(()->client.execute(request,response->{
                    if(response.getCode()!=200)throw new CaptureException("SOURCE_HTTP_"+response.getCode(),"网页未返回成功，请检查URL或改用Firecrawl");
                    String type=response.getEntity()==null?"":Objects.toString(response.getEntity().getContentType(),"").toLowerCase(Locale.ROOT);
                    if(json?!(type.contains("json")||"itunes.apple.com".equals(target.getHost())&&type.startsWith("text/javascript")):!type.contains("text/html")&&!type.contains("application/xhtml+xml"))throw new CaptureException("SOURCE_TYPE_INVALID","来源内容类型不符合请求");
                    try(var input=response.getEntity().getContent()){byte[] bytes=input.readNBytes(RawMaterial.MAX_BYTES+1);return new RawMaterial(target,clock.instant(),"web-http",bytes);}
                }));
                try{return future.get(35,TimeUnit.SECONDS);}catch(Exception e){request.cancel();future.cancel(true);if(e instanceof ExecutionException&&e.getCause() instanceof CaptureException c)throw c;throw e;}
            }
        }catch(CaptureException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CaptureException("WEB_INTERRUPTED","网页采集已中断");}catch(Exception e){throw new CaptureException("WEB_TRANSPORT_FAILED","网页连接失败或超时");}
    }
    private HttpResponse<byte[]> send(HttpRequest request,int timeout) {
        // Self-hosted Node/Express rejects JDK h2c Upgrade requests on its HTTP listener.
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var future=client.sendAsync(request,info->new BoundedBody());
            try{return future.get(timeout,TimeUnit.SECONDS);}catch(Exception e){future.cancel(true);throw e;}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CaptureException("WEB_INTERRUPTED","网页采集已中断");}
        catch(Exception e){throw new CaptureException("WEB_TRANSPORT_FAILED","连接失败、超时或响应超过4MB；请检查抓取服务和网络");}
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> chunks){for(var b:chunks){if(bytes.size()+b.remaining()>RawMaterial.MAX_BYTES){subscription.cancel();result.completeExceptionally(new IllegalStateException("SIZE_LIMIT"));return;}byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}subscription.request(1);}
        public void onError(Throwable e){result.completeExceptionally(e);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
