package com.youkia.iaa.service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
/** URLs remain in evidence; cached files are optional presentation assets. */
public final class IconCache {
 private IconCache(){}
 public static Path directory(){return Path.of(System.getenv().getOrDefault("ICON_DIR","./data/icons")).toAbsolutePath().normalize();}
 public static String key(String url){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public static String display(Object source){if(source==null)return null;String url=String.valueOf(source);String key=key(url);return Files.isRegularFile(directory().resolve(key+".png"))?"/api/v1/media/"+key+".png":url;}
}
