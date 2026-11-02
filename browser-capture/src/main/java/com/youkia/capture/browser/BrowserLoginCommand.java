package com.youkia.capture.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.youkia.capture.CaptureException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Manual login only. Browser closes on a local finish signal, flushing its own persistent session. */
public final class BrowserLoginCommand {
    public static void main(String[] args) {
        try {
            Map<String,String> options = new HashMap<>();
            for (int i=0;i<args.length;i+=2) {
                if (i+1==args.length || !Set.of("--profile","--channel","--finish-file").contains(args[i])) throw new IllegalArgumentException();
                options.put(args[i],args[i+1]);
            }
            Path profile = Path.of(options.getOrDefault("--profile", "data/browser-sessions/gravity"));
            Path finish = Path.of(options.getOrDefault("--finish-file", "data/browser-sessions/gravity/login-finished"));
            if (Files.exists(finish)) throw new CaptureException("LOGIN_SIGNAL_EXISTS", "请使用新的本次登录完成标记路径");
            try (var browser = new BrowserProfile(profile, false, options.getOrDefault("--channel", "chrome"))) {
                Page page = browser.context.pages().isEmpty() ? browser.context.newPage() : browser.context.pages().getFirst();
                page.navigate("https://rank.gravity-engine.com/wechat/day/", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                System.out.println("LOGIN_WINDOW_READY: 请在专用Chrome窗口手动登录；完成后告知助手，保存会话后验证每榜100名。");
                Instant deadline = Instant.now().plusSeconds(1800);
                while (!Files.exists(finish) && Instant.now().isBefore(deadline) && !page.isClosed()) {
                    try { page.waitForTimeout(1000); }
                    catch (PlaywrightException e) { if (page.isClosed()) break; throw e; }
                }
                System.out.println("LOGIN_WINDOW_CLOSED: 浏览器会话已保存，登录有效性将在实际Top100采集中验证。");
            }
        } catch (Exception e) {
            System.err.println(e instanceof CaptureException c ? c.code()+": "+c.getMessage() : "LOGIN_BROWSER_FAILED: 登录浏览器未正常完成");
            System.exit(1);
        }
    }
}
