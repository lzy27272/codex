package cn.sifangguan.hotelaios.integrations.ota;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

@ConfigurationProperties(prefix = "app.ota-authorization.ctrip")
public class CtripPilotBridgeProperties {
    private static final String OPERATOR_CREDENTIAL_NAME = "ctrip-pilot-operator-token";
    private boolean enabled;
    private boolean authorizationEnabled;
    private Path socketPath = OtaPilotScope.SOCKET_PATH;
    private Path operatorTokenFile = defaultOperatorTokenFile();
    private Duration timeout = Duration.ofSeconds(30);
    private int maxResponseBytes = 65_536;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAuthorizationEnabled() {
        return authorizationEnabled;
    }

    public void setAuthorizationEnabled(boolean authorizationEnabled) {
        this.authorizationEnabled = authorizationEnabled;
    }

    public Path getSocketPath() {
        return socketPath;
    }

    public void setSocketPath(Path socketPath) {
        this.socketPath = socketPath;
    }

    public Path getOperatorTokenFile() {
        return operatorTokenFile;
    }

    public void setOperatorTokenFile(Path operatorTokenFile) {
        this.operatorTokenFile = operatorTokenFile;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    private static Path defaultOperatorTokenFile() {
        String credentialsDirectory = System.getenv("CREDENTIALS_DIRECTORY");
        if (credentialsDirectory != null && !credentialsDirectory.isBlank()) {
            return Path.of(credentialsDirectory).resolve(OPERATOR_CREDENTIAL_NAME);
        }
        return Path.of("/etc/hotel-ai-os/ctrip-pilot-operator-token");
    }

    void requireUsable() {
        if (!enabled) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_BRIDGE_DISABLED", "携程云端授权桥接尚未启用");
        }
        if (socketPath == null || !socketPath.normalize().equals(OtaPilotScope.SOCKET_PATH)) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_BRIDGE_CONFIG_INVALID", "携程授权桥接配置无效");
        }
        if (operatorTokenFile == null || !operatorTokenFile.isAbsolute()) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_BRIDGE_CONFIG_INVALID", "携程授权桥接配置无效");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || maxResponseBytes < 1_024 || maxResponseBytes > 65_536) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_BRIDGE_CONFIG_INVALID", "携程授权桥接配置无效");
        }
    }

    char[] readOperatorToken() {
        requireUsable();
        byte[] bytes = null;
        try {
            if (Files.isSymbolicLink(operatorTokenFile)
                    || !Files.isRegularFile(operatorTokenFile, LinkOption.NOFOLLOW_LINKS)) {
                throw OtaAuthorizationException.unavailable(
                        "OTA_AUTHORIZATION_OPERATOR_TOKEN_UNAVAILABLE", "携程授权服务凭据不可用");
            }
            long size = Files.size(operatorTokenFile);
            if (size < 32 || size > 258) {
                throw OtaAuthorizationException.unavailable(
                        "OTA_AUTHORIZATION_OPERATOR_TOKEN_UNAVAILABLE", "携程授权服务凭据不可用");
            }
            try (InputStream input = Files.newInputStream(operatorTokenFile)) {
                bytes = input.readNBytes(259);
                if (bytes.length > 258 || input.read() != -1) {
                    throw OtaAuthorizationException.unavailable(
                            "OTA_AUTHORIZATION_OPERATOR_TOKEN_UNAVAILABLE", "携程授权服务凭据不可用");
                }
            }
            int length = bytes.length;
            while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) {
                length--;
            }
            if (length < 32 || length > 256) {
                throw OtaAuthorizationException.unavailable(
                        "OTA_AUTHORIZATION_OPERATOR_TOKEN_UNAVAILABLE", "携程授权服务凭据不可用");
            }
            char[] token = new char[length];
            for (int index = 0; index < length; index++) {
                int value = Byte.toUnsignedInt(bytes[index]);
                boolean allowed = (value >= 'A' && value <= 'Z')
                        || (value >= 'a' && value <= 'z')
                        || (value >= '0' && value <= '9')
                        || value == '_' || value == '-';
                if (!allowed) {
                    Arrays.fill(token, '\0');
                    throw OtaAuthorizationException.unavailable(
                            "OTA_AUTHORIZATION_OPERATOR_TOKEN_UNAVAILABLE", "携程授权服务凭据不可用");
                }
                token[index] = (char) value;
            }
            return token;
        } catch (OtaAuthorizationException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_OPERATOR_TOKEN_UNAVAILABLE", "携程授权服务凭据不可用");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }
}
