package com.youkia.capture;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Set;

/** Public, fixed hosts only. Redirects are rejected, response reads are bounded. */
public final class HttpMaterialFetcher implements MaterialFetcher {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Clock clock;
    public HttpMaterialFetcher(Clock clock) { this.clock = clock; }
    @Override public RawMaterial fetch(URI url) throws IOException, InterruptedException {
        if (!"https".equals(url.getScheme()) || url.getUserInfo() != null || url.getPort() != -1
                || !Set.of("itunes.apple.com", "www.appbrain.com").contains(url.getHost()))
            throw new CaptureException("SOURCE_URL_REJECTED", "仅允许已注册的公开 HTTPS 来源");
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "YoukiaExternalCapture/1.0 public-source-research").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200)
                throw new CaptureException("SOURCE_HTTP_" + response.statusCode(), "来源未返回成功响应");
            return new RawMaterial(url, clock.instant(), "public-https", body.readNBytes(RawMaterial.MAX_BYTES + 1));
        }
    }
}
