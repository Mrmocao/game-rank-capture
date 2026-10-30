package com.youkia.capture;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Hash the original bytes, not a re-encoded response. */
public record RawMaterial(URI sourceUrl, Instant capturedAt, String transport, byte[] bytes) {
    public static final int MAX_BYTES = 4_000_000;
    public RawMaterial {
        if (sourceUrl == null || capturedAt == null || transport == null || transport.isBlank()
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new CaptureException("INVALID_MATERIAL", "材料及采集时间必填，原始材料不得超过 4MB");
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    public String text() {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\uFEFF", "");
        } catch (CharacterCodingException e) {
            throw new CaptureException("INVALID_ENCODING", "材料必须是有效 UTF-8");
        }
    }
    public String sha256() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
