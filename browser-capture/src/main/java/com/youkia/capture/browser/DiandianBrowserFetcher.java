package com.youkia.capture.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.youkia.capture.*;
import com.youkia.capture.profiles.DiandianProfileParser;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Serial search-by-name transport using a dedicated, manually signed-in browser profile. */
public final class DiandianBrowserFetcher {
    private final Path profile;private final String channel;private final boolean headless;private final Clock clock;
    public DiandianBrowserFetcher(Path profile,String channel,boolean headless,Clock clock){this.profile=profile;this.channel=channel;this.headless=headless;this.clock=clock;}
    public List<RawMaterial> search(String name) throws Exception {
        if(name==null||name.isBlank()||name.length()>500)throw new IllegalArgumentException("Invalid seed name");
        try(var browser=new BrowserProfile(profile,headless,channel)) {
            var page=browser.context.newPage();
            page.navigate("https://app.diandian.com/search/ios-1-5-5-75-0-"+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20"),new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(2000);auth(page);
            try{page.locator("table a[href*='/app/']").first().waitFor();}catch(TimeoutError e){auth(page);throw new CaptureException("PROFILE_SEARCH_STRUCTURE_CHANGED","搜索结果未渲染，等待人工检查");}
            var urls=new LinkedHashSet<String>();
            for(var link:page.locator("table a[href*='/app/']").all()) {
                if(!DiandianProfileParser.candidate(name,link.innerText().strip()))continue;
                URI uri=URI.create(page.url()).resolve(link.getAttribute("href"));
                if(DiandianProfileParser.detailUrl(uri))urls.add(uri.getScheme()+"://"+uri.getHost()+uri.getPath()+"?market=1&country=75");
                if(urls.size()==3)break;
            }
            var result=new ArrayList<RawMaterial>();
            for(String url:urls) {
                page.navigate(url,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                try{page.locator(".app-base-info-wrap").filter(new Locator.FilterOptions().setHasText("Bundle ID")).waitFor();}catch(TimeoutError e){auth(page);throw new CaptureException("PROFILE_FIELDS_MISSING","详情基本信息尚未加载，未保存占位字段");}
                auth(page);
                String json=(String)page.evaluate(PROJECTION);
                var raw=new RawMaterial(URI.create(page.url()),clock.instant(),"diandian-visible-browser",json.getBytes(StandardCharsets.UTF_8));
                var document=new DiandianProfileParser().parse(raw);
                if(!DiandianProfileParser.candidate(name,document.name()))throw new CaptureException("PROFILE_SEARCH_MISMATCH","详情名称与搜索候选不一致");
                result.add(raw);
            }
            return result;
        }catch(CaptureException e){throw e;}catch(PlaywrightException e){throw new CaptureException("PROFILE_BROWSER_FAILED","点点浏览器取材失败，未生成成功数据");}
    }
    private static void auth(Page page) {
        if(page.url().contains("/login")||page.locator("body").innerText().contains("其他登录/注册方式"))throw new CaptureException("SOURCE_AUTH_REQUIRED","点点采集会话需要手动登录");
        if(page.locator("body").innerText().contains("请完成安全验证"))throw new CaptureException("SOURCE_VERIFICATION_REQUIRED","点点需要人工安全验证");
    }
    public static final String PROJECTION="""
        () => {
          const sections = {};
          const allowed = ['基本信息','应用描述','全球速览','热门分类','热门内购','版本记录','新内容'];
          for (const h of document.querySelectorAll('h2')) {
            const label = h.innerText.trim();
            if (allowed.includes(label)) sections[label] = h.parentElement.parentElement.parentElement.innerText;
          }
          return JSON.stringify({format:'diandian-visible-v1',
            name:document.querySelector('.container-head .app-name')?.innerText.split('\\n')[0].trim() || '',
            header:document.querySelector('.container-head')?.innerText || '', sections,
            storeLinks:[...document.querySelectorAll('.container-head a[href*="apps.apple.com"]')].map(a=>a.href)});
        }
        """;
}
