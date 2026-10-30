package com.youkia.capture;

import java.net.URI;

/** Validates source-specific scope and parses supplied material without I/O or business writes. */
public interface SourceAdapter {
    String key();
    URI sourceUrl(CaptureRequest request);
    Observation parse(CaptureRequest request, RawMaterial material);
}
