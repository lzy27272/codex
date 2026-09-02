package cn.sifangguan.hotelaios.integrations.ota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CtripPilotUnixSocketClient {
    private static final int MAX_HEADER_BYTES = 16_384;
    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.1 ([0-9]{3})(?: [^\\r\\n]{0,80})?");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern CHALLENGE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{40,96}");
    private static final Pattern SAFE_STATUS = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Pattern SAFE_REASON = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");
    private static final Set<String> START_STATUSES = Set.of("WAITING_FOR_USER", "COMPLETE", "DISCOVERY_READY");
    private static final Set<String> CREDENTIAL_LOGIN_STATUSES = Set.of(
            "LOGIN_RUNNING", "OTP_REQUIRED", "INTERACTIVE_VERIFICATION_REQUIRED", "AUTHENTICATED", "FAILED");
    private static final Duration MAX_CHALLENGE_TTL = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final CtripPilotBridgeProperties properties;
    private final Clock clock;

    @Autowired
    public CtripPilotUnixSocketClient(ObjectMapper objectMapper, CtripPilotBridgeProperties properties) {
        this(objectMapper, properties, Clock.systemUTC());
    }

    CtripPilotUnixSocketClient(
            ObjectMapper objectMapper,
            CtripPilotBridgeProperties properties,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    PilotStart startDiscovery() {
        return start("/internal/discovery-start");
    }

    PilotStart startAuthorization() {
        return start("/internal/start");
    }

    PilotSession sessionStatus() {
        RawHttpResponse response = exchange("GET", "/internal/session-status");
        try {
            if (response.statusCode() != 200) throw mappedFailure(response);
            return parseSessionData(response.body());
        } finally {
            response.clear();
        }
    }

    PilotCredentialStatus credentialStatus() {
        RawHttpResponse response = exchange("GET", "/internal/credential-status");
        try {
            if (response.statusCode() != 200) throw mappedFailure(response);
            return parseCredentialStatusData(response.body());
        } finally {
            response.clear();
        }
    }

    PilotCredentialStatus saveCredentials(char[] username, char[] password) {
        byte[] body = credentialBody(username, password);
        RawHttpResponse response = exchange("PUT", "/internal/credentials", body);
        try {
            if (response.statusCode() != 200) throw mappedFailure(response);
            return parseCredentialStatusData(response.body());
        } finally {
            response.clear();
            Arrays.fill(body, (byte) 0);
        }
    }

    PilotCredentialChallenge startCredentialLogin() {
        return credentialMutation("/internal/credential-login", new byte[0]);
    }

    PilotCredentialChallenge credentialChallenge(UUID challengeId) {
        RawHttpResponse response = exchange(
                "GET", "/internal/credential-challenge?challengeId=" + challengeId);
        try {
            if (response.statusCode() != 200) throw mappedFailure(response);
            return parseCredentialChallengeData(response.body());
        } finally {
            response.clear();
        }
    }

    PilotCredentialChallenge sendCredentialCode(UUID challengeId) {
        byte[] body = challengeBody(challengeId, null);
        return credentialMutation("/internal/credential-send-code", body);
    }

    PilotCredentialChallenge submitCredentialCode(UUID challengeId, char[] code) {
        byte[] body = challengeBody(challengeId, code);
        return credentialMutation("/internal/credential-submit-code", body);
    }

    PilotSession parseSessionData(byte[] body) {
        JsonNode data = readData(body);
        String bindingStatus = requiredText(data, "bindingStatus");
        if (!Set.of("DISCOVERY_REQUIRED", "BOUND").contains(bindingStatus)) throw invalidResponse();
        String status = requiredText(data, "status");
        if (!Set.of("AUTHORIZED", "REAUTH_REQUIRED").contains(status)) throw invalidResponse();
        OffsetDateTime expiresAt = optionalTimestamp(data, "expiresAt");
        if ("AUTHORIZED".equals(status)) {
            if (!"BOUND".equals(bindingStatus) || expiresAt == null || !expiresAt.isAfter(now())) {
                throw invalidResponse();
            }
        } else if (expiresAt != null) {
            throw invalidResponse();
        }
        return new PilotSession(bindingStatus, status, expiresAt);
    }

    PilotCredentialStatus parseCredentialStatusData(byte[] body) {
        JsonNode data = readData(body);
        if (!OtaPilotScope.HOTEL_ID.toString().equals(requiredText(data, "hotelId"))
                || !OtaPilotScope.HOTEL_CODE.equals(requiredText(data, "hotelCode"))
                || !OtaPilotScope.PLATFORM_CODE.equals(requiredText(data, "platformCode"))
                || !"https://ebooking.ctrip.com/".equals(requiredText(data, "loginUrl"))) {
            throw invalidResponse();
        }
        JsonNode configuredNode = data.get("configured");
        if (configuredNode == null || !configuredNode.isBoolean()) throw invalidResponse();
        boolean configured = configuredNode.booleanValue();
        OffsetDateTime updatedAt = optionalTimestamp(data, "updatedAt");
        String algorithm = optionalText(data, "algorithm");
        if (configured != (updatedAt != null && "AES-256-GCM".equals(algorithm))) throw invalidResponse();
        PilotSession session = parseSessionNode(data.get("session"));
        return new PilotCredentialStatus(configured, updatedAt, algorithm, session);
    }

    PilotCredentialChallenge parseCredentialChallengeData(byte[] body) {
        JsonNode data = readData(body);
        UUID challengeId = parseUuid(requiredText(data, "challengeId"));
        String status = requiredText(data, "status");
        if (!CREDENTIAL_LOGIN_STATUSES.contains(status)) throw invalidResponse();
        String verificationType = optionalText(data, "verificationType");
        String reasonCode = optionalText(data, "reasonCode");
        OffsetDateTime expiresAt = optionalTimestamp(data, "expiresAt");
        JsonNode authenticatedNode = data.get("authenticated");
        if (authenticatedNode == null || !authenticatedNode.isBoolean()) throw invalidResponse();
        boolean authenticated = authenticatedNode.booleanValue();
        String fallbackAuthorizationUrl = optionalText(data, "fallbackAuthorizationUrl");
        if (fallbackAuthorizationUrl != null) validateAuthorizationUrl(fallbackAuthorizationUrl);
        if (expiresAt != null) validateChallengeExpiry(expiresAt);
        if (authenticated != "AUTHENTICATED".equals(status)
                || ("OTP_REQUIRED".equals(status) && verificationType == null)
                || ("INTERACTIVE_VERIFICATION_REQUIRED".equals(status)
                    && (verificationType == null || fallbackAuthorizationUrl == null))
                || (!"INTERACTIVE_VERIFICATION_REQUIRED".equals(status)
                    && fallbackAuthorizationUrl != null)
                || ("FAILED".equals(status) && (reasonCode == null || !SAFE_REASON.matcher(reasonCode).matches()))) {
            throw invalidResponse();
        }
        PilotSession session = parseSessionNode(data.get("session"));
        if (authenticated && !"AUTHORIZED".equals(session.status())) throw invalidResponse();
        return new PilotCredentialChallenge(
                challengeId, status, verificationType, expiresAt, reasonCode,
                authenticated, fallbackAuthorizationUrl, session);
    }

    private PilotSession parseSessionNode(JsonNode session) {
        if (session == null || !session.isObject()) throw invalidResponse();
        String bindingStatus = requiredText(session, "bindingStatus");
        String status = requiredText(session, "status");
        if (!Set.of("DISCOVERY_REQUIRED", "BOUND").contains(bindingStatus)
                || !Set.of("AUTHORIZED", "REAUTH_REQUIRED").contains(status)) throw invalidResponse();
        OffsetDateTime expiresAt = optionalTimestamp(session, "expiresAt");
        if ("AUTHORIZED".equals(status)) {
            if (!"BOUND".equals(bindingStatus) || expiresAt == null || !expiresAt.isAfter(now())) {
                throw invalidResponse();
            }
        } else if (expiresAt != null) throw invalidResponse();
        return new PilotSession(bindingStatus, status, expiresAt);
    }

    private PilotCredentialChallenge credentialMutation(String path, byte[] body) {
        RawHttpResponse response = exchange("POST", path, body);
        try {
            if (response.statusCode() != 202) throw mappedFailure(response);
            return parseCredentialChallengeData(response.body());
        } finally {
            response.clear();
            Arrays.fill(body, (byte) 0);
        }
    }

    private byte[] credentialBody(char[] username, char[] password) {
        if (username == null || password == null) throw invalidResponse();
        SensitiveByteArrayOutputStream output = new SensitiveByteArrayOutputStream(1_024);
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(output)) {
            generator.writeStartObject();
            generator.writeFieldName("username");
            generator.writeString(username, 0, username.length);
            generator.writeFieldName("password");
            generator.writeString(password, 0, password.length);
            generator.writeEndObject();
            generator.flush();
            return output.copy();
        } catch (IOException exception) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_BRIDGE_CONFIG_INVALID", "携程授权桥接配置无效");
        } finally {
            output.wipe();
        }
    }

    private byte[] challengeBody(UUID challengeId, char[] code) {
        if (challengeId == null) throw invalidResponse();
        SensitiveByteArrayOutputStream output = new SensitiveByteArrayOutputStream(256);
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("challengeId", challengeId.toString());
            if (code != null) {
                generator.writeFieldName("code");
                generator.writeString(code, 0, code.length);
            }
            generator.writeEndObject();
            generator.flush();
            return output.copy();
        } catch (IOException exception) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_BRIDGE_CONFIG_INVALID", "携程授权桥接配置无效");
        } finally {
            output.wipe();
        }
    }

    private PilotStart start(String path) {
        RawHttpResponse response = exchange("POST", path);
        try {
            if (response.statusCode() != 202) throw mappedFailure(response);
            return parseStartData(response.body());
        } finally {
            response.clear();
        }
    }

    PilotStart parseStartData(byte[] body) {
        JsonNode data = readData(body);
        UUID challengeId = parseUuid(requiredText(data, "challengeId"));
        String status = requiredText(data, "status");
        if (!SAFE_STATUS.matcher(status).matches() || !START_STATUSES.contains(status)) throw invalidResponse();
        JsonNode requiredNode = data.get("authorizationRequired");
        if (requiredNode == null || !requiredNode.isBoolean()) throw invalidResponse();
        boolean authorizationRequired = requiredNode.booleanValue();
        String authorizationUrl = optionalText(data, "authorizationUrl");
        OffsetDateTime expiresAt = optionalTimestamp(data, "expiresAt");
        if (authorizationRequired) {
            validateAuthorizationUrl(authorizationUrl);
            validateChallengeExpiry(expiresAt);
        } else {
            if (authorizationUrl != null) throw invalidResponse();
            if (expiresAt != null) validateChallengeExpiry(expiresAt);
        }
        String sessionStatus = null;
        JsonNode session = data.get("session");
        if (session != null && !session.isNull()) {
            if (!session.isObject()) throw invalidResponse();
            sessionStatus = requiredText(session, "status");
            if (!Set.of("AUTHORIZED", "REAUTH_REQUIRED").contains(sessionStatus)) throw invalidResponse();
        }
        if ("WAITING_FOR_USER".equals(status) && !authorizationRequired) throw invalidResponse();
        if ("COMPLETE".equals(status)
                && (authorizationRequired || !"AUTHORIZED".equals(sessionStatus))) throw invalidResponse();
        if ("DISCOVERY_READY".equals(status)
                && (authorizationRequired || sessionStatus != null)) throw invalidResponse();
        return new PilotStart(
                challengeId, status, authorizationRequired, authorizationUrl, expiresAt, sessionStatus);
    }

    private RawHttpResponse exchange(String method, String path) {
        return exchange(method, path, new byte[0]);
    }

    private RawHttpResponse exchange(String method, String path, byte[] body) {
        properties.requireUsable();
        char[] token = properties.readOperatorToken();
        byte[] request = null;
        try {
            request = requestBytes(method, path, token, body);
            long deadline = System.nanoTime() + properties.getTimeout().toNanos();
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                 Selector selector = Selector.open()) {
                channel.configureBlocking(false);
                channel.register(selector, 0);
                channel.connect(UnixDomainSocketAddress.of(properties.getSocketPath()));
                while (!channel.finishConnect()) await(channel, selector, SelectionKey.OP_CONNECT, deadline);

                ByteBuffer outbound = ByteBuffer.wrap(request);
                while (outbound.hasRemaining()) {
                    int written = channel.write(outbound);
                    if (written == 0) await(channel, selector, SelectionKey.OP_WRITE, deadline);
                }

                int maximum = MAX_HEADER_BYTES + properties.getMaxResponseBytes();
                ByteArrayOutputStream received = new ByteArrayOutputStream(Math.min(maximum, 8_192));
                ByteBuffer chunk = ByteBuffer.allocate(8_192);
                int expectedTotal = -1;
                while (received.size() <= maximum) {
                    int count = channel.read(chunk);
                    if (count > 0) {
                        chunk.flip();
                        received.write(chunk.array(), chunk.position(), chunk.remaining());
                        chunk.clear();
                        if (received.size() > maximum) throw invalidResponse();
                        if (expectedTotal < 0) expectedTotal = expectedHttpBytes(received.toByteArray(), maximum);
                        if (expectedTotal >= 0 && received.size() == expectedTotal) {
                            return parseHttpResponse(received.toByteArray(), properties.getMaxResponseBytes());
                        }
                        if (expectedTotal >= 0 && received.size() > expectedTotal) throw invalidResponse();
                        continue;
                    }
                    if (count < 0) {
                        return parseHttpResponse(received.toByteArray(), properties.getMaxResponseBytes());
                    }
                    await(channel, selector, SelectionKey.OP_READ, deadline);
                }
                throw invalidResponse();
            } catch (OtaAuthorizationException exception) {
                throw exception;
            } catch (IOException | UnsupportedOperationException exception) {
                throw OtaAuthorizationException.badGateway(
                        "OTA_AUTHORIZATION_PILOT_UNAVAILABLE", "携程云端授权服务暂不可用");
            }
        } finally {
            Arrays.fill(token, '\0');
            if (request != null) Arrays.fill(request, (byte) 0);
        }
    }

    private static void await(
            SocketChannel channel,
            Selector selector,
            int operation,
            long deadline
    ) throws IOException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) throw OtaAuthorizationException.badGateway(
                "OTA_AUTHORIZATION_PILOT_TIMEOUT", "携程云端授权服务响应超时");
        SelectionKey key = channel.keyFor(selector);
        key.interestOps(operation);
        long timeoutMillis = Math.max(1L, Math.min(60_000L, Duration.ofNanos(remainingNanos).toMillis()));
        int ready = selector.select(timeoutMillis);
        selector.selectedKeys().clear();
        key.interestOps(0);
        if (ready == 0 && System.nanoTime() >= deadline) {
            throw OtaAuthorizationException.badGateway(
                    "OTA_AUTHORIZATION_PILOT_TIMEOUT", "携程云端授权服务响应超时");
        }
    }

    private static byte[] requestBytes(String method, String path, char[] token, byte[] body) {
        boolean exactPath = Set.of(
                "/internal/start",
                "/internal/discovery-start",
                "/internal/session-status",
                "/internal/credential-status",
                "/internal/credentials",
                "/internal/credential-login",
                "/internal/credential-send-code",
                "/internal/credential-submit-code"
        ).contains(path);
        boolean challengeQuery = path.matches(
                "/internal/credential-challenge\\?challengeId=[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        if (!Set.of("GET", "POST", "PUT").contains(method)
                || (!exactPath && !challengeQuery)
                || body == null
                || body.length > 4_096
                || (("GET".equals(method)) && body.length != 0)) {
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_BRIDGE_CONFIG_INVALID", "携程授权桥接配置无效");
        }
        byte[] prefix = (method + " " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\nAuthorization: Pilot ").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\nAccept: application/json\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] request = new byte[prefix.length + token.length + suffix.length + body.length];
        System.arraycopy(prefix, 0, request, 0, prefix.length);
        int offset = prefix.length;
        for (char value : token) request[offset++] = (byte) value;
        System.arraycopy(suffix, 0, request, offset, suffix.length);
        System.arraycopy(body, 0, request, offset + suffix.length, body.length);
        return request;
    }

    static RawHttpResponse parseHttpResponse(byte[] response, int maxBodyBytes) {
        if (response == null || response.length == 0
                || maxBodyBytes < 1 || response.length > MAX_HEADER_BYTES + maxBodyBytes) {
            throw invalidResponse();
        }
        int separator = headerEnd(response);
        if (separator < 0 || separator > MAX_HEADER_BYTES) throw invalidResponse();
        String head = new String(response, 0, separator, StandardCharsets.US_ASCII);
        String[] lines = head.split("\\r\\n", -1);
        if (lines.length < 2) throw invalidResponse();
        Matcher statusMatcher = STATUS_LINE.matcher(lines[0]);
        if (!statusMatcher.matches()) throw invalidResponse();
        int status = Integer.parseInt(statusMatcher.group(1));
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty() || line.startsWith(" ") || line.startsWith("\t")) throw invalidResponse();
            int colon = line.indexOf(':');
            if (colon <= 0) throw invalidResponse();
            String name = line.substring(0, colon);
            if (!HEADER_NAME.matcher(name).matches()) throw invalidResponse();
            String normalized = name.toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.putIfAbsent(normalized, value) != null) throw invalidResponse();
        }
        if (headers.containsKey("transfer-encoding")) throw invalidResponse();
        String contentType = headers.get("content-type");
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!(normalizedContentType.equals("application/json")
                || normalizedContentType.startsWith("application/json;"))) {
            throw invalidResponse();
        }
        int contentLength = parseContentLength(headers.get("content-length"), maxBodyBytes);
        int bodyOffset = separator + 4;
        if (response.length - bodyOffset != contentLength) throw invalidResponse();
        return new RawHttpResponse(status, Arrays.copyOfRange(response, bodyOffset, response.length));
    }

    private static int expectedHttpBytes(byte[] response, int maximum) {
        int separator = headerEnd(response);
        if (separator < 0) {
            if (response.length > MAX_HEADER_BYTES) throw invalidResponse();
            return -1;
        }
        if (separator > MAX_HEADER_BYTES) throw invalidResponse();
        String head = new String(response, 0, separator, StandardCharsets.US_ASCII);
        String contentLength = null;
        for (String line : head.split("\\r\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon))) {
                if (contentLength != null) throw invalidResponse();
                contentLength = line.substring(colon + 1).trim();
            }
        }
        int length = parseContentLength(contentLength, maximum - MAX_HEADER_BYTES);
        long total = (long) separator + 4L + length;
        if (total > maximum) throw invalidResponse();
        return (int) total;
    }

    private static int parseContentLength(String value, int maximum) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,5})")) throw invalidResponse();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > maximum) throw invalidResponse();
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalidResponse();
        }
    }

    private static int headerEnd(byte[] value) {
        for (int index = 0; index <= value.length - 4; index++) {
            if (value[index] == '\r' && value[index + 1] == '\n'
                    && value[index + 2] == '\r' && value[index + 3] == '\n') return index;
        }
        return -1;
    }

    private JsonNode readData(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isObject()) throw invalidResponse();
            return data;
        } catch (OtaAuthorizationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalidResponse();
        }
    }

    private OtaAuthorizationException mappedFailure(RawHttpResponse response) {
        String code = null;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root != null && root.path("code").isTextual()) code = root.path("code").asText();
        } catch (IOException ignored) {
            // The upstream body is intentionally not copied into any error or log.
        }
        if (response.statusCode() == 409
                && "OTA_CTRIP_CLOUD_HOTEL_IDENTITY_DISCOVERY_REQUIRED".equals(code)) {
            return OtaAuthorizationException.conflict(
                    code, "该门店尚未完成携程稳定身份绑定，请先执行身份发现");
        }
        if (response.statusCode() == 409
                && "OTA_CTRIP_CLOUD_HOTEL_IDENTITY_ALREADY_CONFIGURED".equals(code)) {
            return OtaAuthorizationException.conflict(
                    code, "该门店已完成携程稳定身份绑定，请直接重新授权");
        }
        return OtaAuthorizationException.badGateway(
                "OTA_AUTHORIZATION_PILOT_REJECTED", "携程云端授权服务未接受本次操作");
    }

    private void validateAuthorizationUrl(String raw) {
        if (raw == null || !raw.startsWith(OtaPilotScope.AUTHORIZATION_URL_PREFIX)) throw invalidResponse();
        try {
            URI uri = new URI(raw);
            String fragment = uri.getRawFragment();
            if (!"https".equals(uri.getScheme())
                    || !"www.sfgzt.cn".equals(uri.getHost())
                    || uri.getPort() != -1
                    || uri.getRawUserInfo() != null
                    || !"/ota-pilot/authorize".equals(uri.getRawPath())
                    || uri.getRawQuery() != null
                    || fragment == null
                    || !CHALLENGE_TOKEN.matcher(fragment).matches()
                    || !raw.equals(OtaPilotScope.AUTHORIZATION_URL_PREFIX + fragment)) {
                throw invalidResponse();
            }
        } catch (URISyntaxException exception) {
            throw invalidResponse();
        }
    }

    private void validateChallengeExpiry(OffsetDateTime expiresAt) {
        if (expiresAt == null) throw invalidResponse();
        Duration remaining = Duration.between(now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero() || remaining.compareTo(MAX_CHALLENGE_TTL) > 0) {
            throw invalidResponse();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalidResponse();
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalidResponse();
        return value.asText();
    }

    private static OffsetDateTime optionalTimestamp(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException exception) {
            throw invalidResponse();
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
    }

    private static OtaAuthorizationException invalidResponse() {
        return OtaAuthorizationException.badGateway(
                "OTA_AUTHORIZATION_PILOT_RESPONSE_INVALID", "携程云端授权服务返回了无效响应");
    }

    record PilotStart(
            UUID challengeId,
            String status,
            boolean authorizationRequired,
            String authorizationUrl,
            OffsetDateTime expiresAt,
            String sessionStatus
    ) {
    }

    record PilotSession(String bindingStatus, String status, OffsetDateTime expiresAt) {
    }

    record PilotCredentialStatus(
            boolean configured,
            OffsetDateTime updatedAt,
            String algorithm,
            PilotSession session
    ) {
    }

    record PilotCredentialChallenge(
            UUID challengeId,
            String status,
            String verificationType,
            OffsetDateTime expiresAt,
            String reasonCode,
            boolean authenticated,
            String fallbackAuthorizationUrl,
            PilotSession session
    ) {
    }

    private static final class SensitiveByteArrayOutputStream extends ByteArrayOutputStream {
        SensitiveByteArrayOutputStream(int size) {
            super(size);
        }

        byte[] copy() {
            return Arrays.copyOf(buf, count);
        }

        void wipe() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    record RawHttpResponse(int statusCode, byte[] body) {
        void clear() {
            Arrays.fill(body, (byte) 0);
        }
    }
}
