package com.youkia.capture;

/** Stable collection error, independent of HTTP controllers and business services. */
public final class CaptureException extends RuntimeException {
    private final String code;
    public CaptureException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
