package cn.sifangguan.hotelaios.integrations.wecom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** Callback protocol instance isolated from task-card and group-delivery flags. */
@Component
@ConditionalOnProperty(
        name = {"app.wecom.enabled", "app.wecom.directory-sync-enabled"}, havingValue = "true"
)
public class WeComDirectoryCallbackCrypto {
    private static final int WECOM_BLOCK_SIZE = 32;
    private final WeComDirectoryProperties properties;
    private final byte[] aesKey;

    public WeComDirectoryCallbackCrypto(WeComDirectoryProperties properties) {
        this.properties = properties;
        try {
            this.aesKey = Base64.getDecoder().decode(properties.callbackAesKey() + "=");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Directory callback AES key is invalid", exception);
        }
        if (aesKey.length != 32) {
            throw new IllegalStateException("Directory callback AES key must decode to 32 bytes");
        }
    }

    public String verifyAndDecrypt(String signature, String timestamp, String nonce, String encrypted) {
        if (!signatureMatches(signature, timestamp, nonce, encrypted)) {
            throw new IllegalArgumentException("WeCom directory callback signature is invalid");
        }
        return decrypt(encrypted);
    }

    boolean signatureMatches(String signature, String timestamp, String nonce, String encrypted) {
        if (blank(signature) || blank(timestamp) || blank(nonce) || blank(encrypted)) return false;
        byte[] expected = signature(timestamp, nonce, encrypted).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    String signature(String timestamp, String nonce, String encrypted) {
        try {
            List<String> parts = new ArrayList<>(List.of(
                    properties.callbackToken(), timestamp, nonce, encrypted));
            Collections.sort(parts);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    String decrypt(String encrypted) {
        try {
            byte[] ciphertext = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, 16));
            byte[] plain = unpad(cipher.doFinal(ciphertext));
            if (plain.length < 20) throw new IllegalArgumentException("Directory callback is too short");
            int messageLength = ByteBuffer.wrap(plain, 16, 4).getInt();
            if (messageLength < 0 || 20L + messageLength > plain.length) {
                throw new IllegalArgumentException("Directory callback length is invalid");
            }
            String receiveId = new String(plain, 20 + messageLength,
                    plain.length - 20 - messageLength, StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(receiveId.getBytes(StandardCharsets.UTF_8),
                    properties.receiveId().getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Directory callback receiver does not match this deployment");
            }
            return new String(plain, 20, messageLength, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to decrypt WeCom directory callback");
        }
    }

    private static byte[] unpad(byte[] input) {
        if (input.length == 0) throw new IllegalArgumentException("Directory callback padding is missing");
        int padding = Byte.toUnsignedInt(input[input.length - 1]);
        if (padding < 1 || padding > WECOM_BLOCK_SIZE || padding > input.length) {
            throw new IllegalArgumentException("Directory callback padding is invalid");
        }
        for (int index = input.length - padding; index < input.length; index++) {
            if (Byte.toUnsignedInt(input[index]) != padding) {
                throw new IllegalArgumentException("Directory callback padding is invalid");
            }
        }
        return java.util.Arrays.copyOf(input, input.length - padding);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
