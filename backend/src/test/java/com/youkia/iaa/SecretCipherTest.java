package com.youkia.iaa;

import com.youkia.iaa.service.SecretCipher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretCipherTest {
    @Test void encryptsAndDecryptsWithoutReturningPlaintext() {
        SecretCipher cipher=new SecretCipher("test-key");
        String encoded=cipher.encrypt("sk-secret");
        assertNotEquals("sk-secret",encoded); assertEquals("sk-secret",cipher.decrypt(encoded));
    }
}
