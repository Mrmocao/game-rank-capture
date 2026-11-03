package com.youkia.capture;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Fixed public routes, bounded body, whole-exchange deadline and at most three transient attempts. */
public final class HttpMaterialFetcher implements MaterialFetcher {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Clock clock;
    public HttpMaterialFetcher(Clock clock) { this.clock = clock; }
    @Override public RawMaterial fetch(URI url) throws IOException, InterruptedException {
        validateUrl(url);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "YoukiaExternalCapture/1.1 public-source-research")
                        .header("Accept", "application/json,text/html;q=0.9").GET().build();
                var future = client.sendAsync(request, ignored -> new LimitedBodySubscriber(RawMaterial.MAX_BYTES));
                HttpResponse<byte[]> response;
                try { response = future.get(30, TimeUnit.SECONDS); }
                catch (TimeoutException e) { future.cancel(true); throw new HttpTimeoutException("Source deadline exceeded"); }
                catch (InterruptedException e) { future.cancel(true); throw e; }
                catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    while (cause.getCause() != null && !(cause instanceof CaptureException)) cause = cause.getCause();
                    if (cause instanceof CaptureException c) throw c;
                    if (cause instanceof IOException io) throw io;
                    throw new IOException("Source exchange failed");
                }
                int status = response.statusCode();
                if (status == 200) return new RawMaterial(url, clock.instant(), "public-https", response.body());
                if (attempt < 3 && (status == 429 || status == 502 || status == 503 || status == 504)) {
                    Thread.sleep(attempt * 1000L); continue;
                }
                throw new CaptureException("SOURCE_HTTP_" + status, "来源未返回成功响应");
            } catch (IOException e) {
                if (attempt == 3) throw e;
                Thread.sleep(attempt * 1000L);
            }
        }
        throw new IllegalStateException("Unreachable");
    }
    static void validateUrl(URI url) {
        boolean route = "itunes.apple.com".equals(url.getHost()) && url.getPath().matches("/(cn|us|gb|de|fr)/rss/(topfreeapplications|toppaidapplications|topgrossingapplications)/limit=([1-9][0-9]?|100)/genre=6014/json")
                || "www.appbrain.com".equals(url.getHost()) && url.getPath().matches("/stats/google-play-rankings/(top_free|top_paid|top_grossing|top_new_free|top_new_paid)/game/(us|gb|de|fr)")
                || "rank.gravity-engine.com".equals(url.getHost()) && url.getPath().matches("/(wechat|douyin)/day/");
        if (!"https".equals(url.getScheme()) || url.getUserInfo() != null || url.getPort() != -1
                || url.getQuery() != null || url.getFragment() != null || !route)
            throw new CaptureException("SOURCE_URL_REJECTED", "只允许已注册的公开HTTPS榜单路径");
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new CaptureException("SOURCE_RESPONSE_TOO_LARGE", "来源响应超过4MB")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
