package com.youkia.capture;

import java.net.URI;
import java.io.IOException;

@FunctionalInterface
public interface MaterialFetcher {
    RawMaterial fetch(URI sourceUrl) throws IOException, InterruptedException;
}
