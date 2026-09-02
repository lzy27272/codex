package cn.sifangguan.hotelaios.integrations.ota;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CtripPilotBridgePropertiesTest {
    @TempDir
    Path tempDir;

    @Test
    void readsOnlyDedicatedBoundedBase64UrlTokenFile() throws Exception {
        Path tokenFile = tempDir.resolve("ctrip-pilot-operator-token");
        Files.writeString(tokenFile, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n");
        CtripPilotBridgeProperties properties = enabled(tokenFile);

        char[] token = properties.readOperatorToken();
        try {
            assertThat(token).hasSize(40);
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    @Test
    void rejectsHeaderInjectionAndDoesNotExposeTokenInError() throws Exception {
        Path tokenFile = tempDir.resolve("bad-token");
        String secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        Files.writeString(tokenFile, secret + "\r\nInjected: value");
        CtripPilotBridgeProperties properties = enabled(tokenFile);

        assertThatThrownBy(properties::readOperatorToken)
                .isInstanceOf(OtaAuthorizationException.class)
                .hasMessageNotContaining(secret)
                .hasNoCause();
    }

    private static CtripPilotBridgeProperties enabled(Path tokenFile) {
        CtripPilotBridgeProperties properties = new CtripPilotBridgeProperties();
        properties.setEnabled(true);
        properties.setOperatorTokenFile(tokenFile);
        return properties;
    }
}
