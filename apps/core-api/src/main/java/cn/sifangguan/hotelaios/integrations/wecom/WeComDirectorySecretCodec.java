package cn.sifangguan.hotelaios.integrations.wecom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Encrypts provider identifiers at rest and creates non-reversible lookup fingerprints. */
@Component
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComDirectorySecretCodec {
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private final byte[] key;
    private final String corpId;
    private final SecureRandom random = new SecureRandom();

    public WeComDirectorySecretCodec(WeComDirectoryProperties properties) {
        this.key = Base64.getDecoder().decode(properties.encryptionKey());
        this.corpId = properties.corpId();
    }

    public String encrypt(String plaintext) {
        String value = required(plaintext);
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(corpId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt WeCom directory identity", exception);
        }
    }

    public String decrypt(String ciphertext) {
        String value = required(ciphertext);
        try {
            byte[] packed = Base64.getUrlDecoder().decode(value);
            if (packed.length <= GCM_IV_BYTES + 16) {
                throw new IllegalArgumentException("Encrypted WeCom identity is invalid");
            }
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, GCM_IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, GCM_IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(corpId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to decrypt WeCom directory identity");
        }
    }

    public String fingerprint(String userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (corpId + ":" + required(userId)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint WeCom directory identity", exception);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(required(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Sensitive value is missing");
        return value.trim();
    }
}
