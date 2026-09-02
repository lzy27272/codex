package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComDirectorySecretCodecTest {
    private WeComDirectorySecretCodec codec;

    @BeforeEach
    void setUp() {
        WeComDirectoryProperties properties = mock(WeComDirectoryProperties.class);
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) (index + 1);
        when(properties.encryptionKey()).thenReturn(Base64.getEncoder().encodeToString(key));
        when(properties.corpId()).thenReturn("corp-test");
        codec = new WeComDirectorySecretCodec(properties);
    }

    @Test
    void aesGcmRoundTripsWithoutExposingPlaintext() {
        String plaintext = "employee-sensitive-id";

        String ciphertext = codec.encrypt(plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(codec.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void fingerprintIsStableAndSpecificToTheUserId() {
        String first = codec.fingerprint("employee-001");

        assertThat(codec.fingerprint("employee-001")).isEqualTo(first);
        assertThat(codec.fingerprint("employee-002")).isNotEqualTo(first);
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsTamperedCiphertext() {
        byte[] tampered = Base64.getUrlDecoder().decode(codec.encrypt("employee-001"));
        tampered[tampered.length - 1] ^= 1;
        String ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered);

        assertThatThrownBy(() -> codec.decrypt(ciphertext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to decrypt");
    }
}
