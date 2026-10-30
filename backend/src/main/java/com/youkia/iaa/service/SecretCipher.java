package com.youkia.iaa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SecretCipher {
    private final byte[] key; private final SecureRandom random = new SecureRandom();
    public SecretCipher(@Value("${app.config-encryption-key:dev-only-change-me}") String configured) { try { key=MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8)); } catch(Exception e){throw new IllegalStateException(e);} }
    public String encrypt(String value) { try { byte[] iv=new byte[12]; random.nextBytes(iv); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] out=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,out,0,iv.length);System.arraycopy(encrypted,0,out,iv.length,encrypted.length);return Base64.getEncoder().encodeToString(out); } catch(Exception e){throw new IllegalStateException("无法加密配置",e);} }
    public String decrypt(String encoded) { try { byte[] all=Base64.getDecoder().decode(encoded);byte[] iv=java.util.Arrays.copyOf(all,12);byte[] encrypted=java.util.Arrays.copyOfRange(all,12,all.length);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8); } catch(Exception e){throw new IllegalStateException("无法解密配置",e);} }
}
