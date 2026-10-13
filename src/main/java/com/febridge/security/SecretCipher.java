package com.febridge.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SecretCipher {

    private final String masterKey;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(
        @Value("${FEBRIDGE_MASTER_KEY:${febridge.security.master-key:}}") String masterKey
    ) {
        this.masterKey = masterKey;
    }

    private SecretKeySpec key() {
        try {
            byte[] decoded = Base64.getDecoder().decode(masterKey);
            if (decoded.length != 32) {
                throw new IllegalArgumentException();
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "请配置 32 字节 Base64 加密主密钥"
            );
        }
    }

    public String encrypt(String secret) {
        var key = key();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(output);
        } catch (GeneralSecurityException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "密钥加密失败");
        }
    }

    public String decrypt(String encrypted) {
        var key = key();
        try {
            if (!encrypted.startsWith("v1:")) {
                throw new IllegalArgumentException();
            }
            byte[] input = Base64.getDecoder().decode(encrypted.substring(3));
            if (input.length < 28) {
                throw new IllegalArgumentException();
            }
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(128, Arrays.copyOfRange(input, 0, 12))
            );
            return new String(
                cipher.doFinal(Arrays.copyOfRange(input, 12, input.length)),
                StandardCharsets.UTF_8
            );
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "无法解密提供商密钥，请检查加密主密钥"
            );
        }
    }
}
