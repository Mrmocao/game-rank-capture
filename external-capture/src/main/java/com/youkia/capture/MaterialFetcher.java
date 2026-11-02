package com.youkia.capture;

import java.net.URI;
import java.io.IOException;

@FunctionalInterface
public interface MaterialFetcher {
    RawMaterial fetch(URI sourceUrl) throws IOException, InterruptedException;

    /** Date selection is optional; unsupported transports still validate the returned source date. */
    default RawMaterial fetch(URI sourceUrl, java.time.LocalDate requestedSourceDate) throws IOException, InterruptedException {
        return fetch(sourceUrl);
    }
}
