package com.nightvault.backend.analysis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.nightvault.backend.api.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class AnalysisPayloads {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final JsonMapper json;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;

    AnalysisPayloads(JsonMapper json, @Value("${NIGHTVAULT_DATA_KEY:}") String configuredKey) {
        this.json = json;
        if (configuredKey.isBlank()) {
            encryptionKey = null;
            fingerprintKey = null;
            return;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("NIGHTVAULT_DATA_KEY must be a Base64-encoded 32-byte key.");
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("NIGHTVAULT_DATA_KEY must be a Base64-encoded 32-byte key.");
        }
        encryptionKey = new SecretKeySpec(key, "AES");
        fingerprintKey = new SecretKeySpec(
            mac(new SecretKeySpec(key, "HmacSHA256"), bytes("nightvault-analysis-fingerprint-v1")), "HmacSHA256");
    }

    boolean configured() {
        return encryptionKey != null;
    }

    byte[] fingerprint(WorryInput input) {
        requireKey();
        return mac(fingerprintKey, json.writeValueAsBytes(input));
    }

    byte[] contextHash(UUID context) {
        requireKey();
        return mac(fingerprintKey, bytes("context:" + context));
    }

    byte[] encryptInput(UUID requestId, WorryInput input) {
        return encrypt("input:" + requestId, json.writeValueAsBytes(input));
    }

    WorryInput decryptInput(UUID requestId, byte[] encrypted) {
        requireKey();
        ByteBuffer buffer = ByteBuffer.wrap(encrypted);
        byte[] nonce = new byte[NONCE_LENGTH];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return json.readValue(crypt(Cipher.DECRYPT_MODE, "input:" + requestId, nonce, ciphertext), WorryInput.class);
    }

    byte[] encryptContent(UUID requestId, AnalysisContent content) {
        return encrypt("content:" + requestId, json.writeValueAsBytes(content));
    }

    private byte[] encrypt(String purpose, byte[] plaintext) {
        requireKey();
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, purpose, nonce, plaintext);
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    private byte[] crypt(int mode, String purpose, byte[] nonce, byte[] payload) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(bytes(purpose));
            return cipher.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Analysis payload could not be processed", exception);
        }
    }

    private void requireKey() {
        if (!configured()) {
            throw new AnalysisException(ApiError.TEMPORARILY_UNAVAILABLE);
        }
    }

    private static byte[] mac(SecretKeySpec key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Analysis fingerprint is unavailable", exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
