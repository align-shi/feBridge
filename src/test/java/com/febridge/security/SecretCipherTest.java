package com.febridge.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SecretCipherTest {

    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithFreshNonceAndAuthenticatesCiphertext() {
        var cipher = new SecretCipher(key);
        String first = cipher.encrypt("test-secret"),
            second = cipher.encrypt("test-secret");
        assertNotEquals(first, second);
        assertFalse(first.contains("test-secret"));
        assertEquals("test-secret", cipher.decrypt(first));
        byte[] bytes = Base64.getDecoder().decode(first.substring(3));
        bytes[bytes.length - 1] ^= 1;
        assertThrows(ResponseStatusException.class, () ->
            cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes))
        );
    }

    @Test
    void rejectsMissingAndWrongMasterKeys() {
        assertThrows(ResponseStatusException.class, () -> new SecretCipher("").encrypt("test"));
        var encrypted = new SecretCipher(key).encrypt("test");
        byte[] wrong = new byte[32];
        wrong[0] = 1;
        assertThrows(ResponseStatusException.class, () ->
            new SecretCipher(Base64.getEncoder().encodeToString(wrong)).decrypt(encrypted)
        );
    }
}
