package com.youkia.capture.browser;

import com.microsoft.playwright.*;
import com.youkia.capture.CaptureException;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Map;

/** A separate profile; never opens, exports or copies the user's normal browser credentials. */
final class BrowserProfile implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    final Playwright playwright;
    final BrowserContext context;
    BrowserProfile(Path directory, boolean headless, String browserChannel) throws IOException {
        Path root = directory.toAbsolutePath().normalize(); Files.createDirectories(root);
        channel = FileChannel.open(root.resolve("capture-session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); }
        catch (OverlappingFileLockException e) { channel.close(); throw new CaptureException("BROWSER_PROFILE_BUSY", "采集浏览器正在使用，请关闭登录窗口后重试"); }
        if (acquired == null) { channel.close(); throw new CaptureException("BROWSER_PROFILE_BUSY", "采集浏览器正在使用，请关闭登录窗口后重试"); }
        lock = acquired;
        Playwright runtime = null;
        try {
            runtime = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            var options = new BrowserType.LaunchPersistentContextOptions().setChannel(browserChannel).setHeadless(headless)
                    .setViewportSize(1440, 1000).setTimeout(30_000);
            context = runtime.chromium().launchPersistentContext(root.resolve("profile"), options);
            context.setDefaultTimeout(15_000); context.setDefaultNavigationTimeout(30_000);
            playwright = runtime;
        } catch (RuntimeException e) {
            if (runtime != null) runtime.close(); lock.release(); channel.close();
            throw new CaptureException("BROWSER_START_FAILED", "无法启动专用采集浏览器，请检查Chrome安装及会话目录");
        }
    }
    @Override public void close() throws IOException {
        try { context.close(); } finally { try { playwright.close(); } finally { lock.release(); channel.close(); } }
    }
}
