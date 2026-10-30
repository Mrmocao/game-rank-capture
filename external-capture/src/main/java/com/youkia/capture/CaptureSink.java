package com.youkia.capture;

import java.io.IOException;

/** Consumers decide storage; the collection core never imports their implementation. */
@FunctionalInterface
public interface CaptureSink {
    void save(Observation.Bundle bundle, RawMaterial material) throws IOException;
}
