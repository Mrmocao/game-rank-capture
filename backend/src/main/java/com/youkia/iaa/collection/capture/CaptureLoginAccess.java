package com.youkia.iaa.collection.capture;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** Separate short-lived maintenance access; never stores source-site passwords. */
public final class CaptureLoginAccess {
    private final String code;private final Clock clock;
    private final Map<String,Instant> sessions=new ConcurrentHashMap<>();
    private int failures;private Instant blockedUntil=Instant.MIN;
    public CaptureLoginAccess(String code,Clock clock){this.code=code;this.clock=clock;}
    public boolean configured(){return code.length()>=24;}
    public synchronized String login(String supplied){
        if(clock.instant().isBefore(blockedUntil))return null;
        if(!configured()||supplied==null||!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){
            if(++failures>=10){blockedUntil=clock.instant().plusSeconds(900);failures=0;}return null;
        }
        failures=0;sessions.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue()));
        if(sessions.size()>=20)sessions.clear();
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=HexFormat.of().formatHex(bytes);
        sessions.put(token,clock.instant().plusSeconds(1800));return token;
    }
    public boolean valid(String token){return token!=null&&clock.instant().isBefore(sessions.getOrDefault(token,Instant.MIN));}
}
