package com.youkia.iaa.collection.capture;

import com.youkia.capture.web.*;
import com.youkia.iaa.service.SecretCipher;
import java.nio.file.Path;
import java.util.List;

public final class GameMetricFactory {
    private GameMetricFactory() {}
    public static GameMetricService create(CaptureWorkspaceStore store,OnlineWebCaptureService web,Path root,String trusted,String key) {
        var transport=new WebTransport(store.mapper,store.clock,trusted);
        return new GameMetricService(store,(tenant,target)->{
            if(target.source().equals("apple-lookup"))return transport.fetchJson(target.url());
            var cfg=store.jdbc.queryForMap("SELECT transport FROM capture_metric_config WHERE tenant_id=?",tenant);
            String tool=(String)cfg.get("transport");
            if(tool.equals("web-http")&&target.platform().equals("IOS"))return transport.fetchStoreHtml(target.url());
            var rule=new WebCaptureRule(target.url().toString(),"html",List.of(new WebCaptureRule.Field("text","","text",true)),1,1,0,false);
            return transport.fetch(tool,rule,web.runtime(tenant,tool));
        },root,new SecretCipher(key.isBlank()?"unused-no-credentials":key),!key.isBlank());
    }
}
