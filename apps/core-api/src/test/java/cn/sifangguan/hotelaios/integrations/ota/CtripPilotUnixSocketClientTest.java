package cn.sifangguan.hotelaios.integrations.ota;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CtripPilotUnixSocketClientTest {
    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private final CtripPilotUnixSocketClient client = new CtripPilotUnixSocketClient(
            new ObjectMapper(), new CtripPilotBridgeProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsOnlyExactPublicAuthorizationUrlAndTenMinuteChallenge() {
        byte[] body = ("""
                {"data":{"challengeId":"11111111-1111-4111-8111-111111111111",
                "status":"WAITING_FOR_USER","authorizationRequired":true,
                "authorizationUrl":"https://www.sfgzt.cn/ota-pilot/authorize#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "expiresAt":"2026-08-29T12:09:59Z","sessionPersisted":false}}
                """).getBytes(StandardCharsets.UTF_8);

        CtripPilotUnixSocketClient.PilotStart result = client.parseStartData(body);

        assertThat(result.authorizationRequired()).isTrue();
        assertThat(result.authorizationUrl()).startsWith(OtaPilotScope.AUTHORIZATION_URL_PREFIX);
    }

    @Test
    void rejectsLookalikeAuthorizationHostAndOverlongTtl() {
        byte[] wrongHost = ("""
                {"data":{"challengeId":"11111111-1111-4111-8111-111111111111",
                "status":"WAITING_FOR_USER","authorizationRequired":true,
                "authorizationUrl":"https://www.sfgzt.cn.evil.test/ota-pilot/authorize#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "expiresAt":"2026-08-29T12:09:59Z"}}
                """).getBytes(StandardCharsets.UTF_8);
        byte[] overlong = ("""
                {"data":{"challengeId":"11111111-1111-4111-8111-111111111111",
                "status":"WAITING_FOR_USER","authorizationRequired":true,
                "authorizationUrl":"https://www.sfgzt.cn/ota-pilot/authorize#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "expiresAt":"2026-08-29T12:10:01Z"}}
                """).getBytes(StandardCharsets.UTF_8);

        assertInvalid(() -> client.parseStartData(wrongHost));
        assertInvalid(() -> client.parseStartData(overlong));
    }

    @Test
    void validatesSafeBindingAndSessionStatusProjection() {
        var unbound = client.parseSessionData(
                "{\"data\":{\"bindingStatus\":\"DISCOVERY_REQUIRED\",\"status\":\"REAUTH_REQUIRED\",\"expiresAt\":null}}"
                        .getBytes(StandardCharsets.UTF_8));
        var bound = client.parseSessionData(
                "{\"data\":{\"bindingStatus\":\"BOUND\",\"status\":\"AUTHORIZED\",\"expiresAt\":\"2026-09-28T12:00:00Z\"}}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(unbound.bindingStatus()).isEqualTo("DISCOVERY_REQUIRED");
        assertThat(bound.status()).isEqualTo("AUTHORIZED");
        assertThatThrownBy(() -> client.parseSessionData(
                "{\"data\":{\"bindingStatus\":\"DISCOVERY_REQUIRED\",\"status\":\"AUTHORIZED\",\"expiresAt\":\"2026-09-28T12:00:00Z\"}}"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(OtaAuthorizationException.class);
    }

    @Test
    void validatesEncryptedCredentialStatusAndOtpChallengeProjection() {
        var credential = client.parseCredentialStatusData(("""
                {"data":{"hotelId":"20000000-0000-4000-8000-000000000002",
                "hotelCode":"002","platformCode":"CTRIP","loginUrl":"https://ebooking.ctrip.com/",
                "configured":true,"updatedAt":"2026-08-29T11:00:00Z","algorithm":"AES-256-GCM",
                "session":{"bindingStatus":"BOUND","status":"REAUTH_REQUIRED","expiresAt":null}}}
                """).getBytes(StandardCharsets.UTF_8));
        var challenge = client.parseCredentialChallengeData(("""
                {"data":{"challengeId":"11111111-1111-4111-8111-111111111111",
                "status":"OTP_REQUIRED","verificationType":"ONE_TIME_CODE",
                "expiresAt":"2026-08-29T12:09:59Z","reasonCode":null,"authenticated":false,
                "fallbackAuthorizationUrl":null,
                "session":{"bindingStatus":"BOUND","status":"REAUTH_REQUIRED","expiresAt":null}}}
                """).getBytes(StandardCharsets.UTF_8));

        assertThat(credential.configured()).isTrue();
        assertThat(credential.algorithm()).isEqualTo("AES-256-GCM");
        assertThat(challenge.status()).isEqualTo("OTP_REQUIRED");
        assertThat(challenge.authenticated()).isFalse();
    }

    @Test
    void strictHttpParserRejectsChunkingDuplicatesAndLengthMismatch() {
        byte[] body = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] valid = response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\n\r\n", body);
        assertThat(CtripPilotUnixSocketClient.parseHttpResponse(valid, 1024).statusCode()).isEqualTo(200);

        byte[] chunked = response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\nContent-Length: " + body.length + "\r\n\r\n", body);
        byte[] duplicate = response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\nContent-Length: " + body.length + "\r\n\r\n", body);
        byte[] mismatch = response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: 99\r\n\r\n", body);

        assertInvalid(() -> CtripPilotUnixSocketClient.parseHttpResponse(chunked, 1024));
        assertInvalid(() -> CtripPilotUnixSocketClient.parseHttpResponse(duplicate, 1024));
        assertInvalid(() -> CtripPilotUnixSocketClient.parseHttpResponse(mismatch, 1024));
    }

    private static byte[] response(String head, byte[] body) {
        byte[] header = head.getBytes(StandardCharsets.US_ASCII);
        byte[] value = new byte[header.length + body.length];
        System.arraycopy(header, 0, value, 0, header.length);
        System.arraycopy(body, 0, value, header.length, body.length);
        return value;
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(OtaAuthorizationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("OTA_AUTHORIZATION_PILOT_RESPONSE_INVALID"));
    }
}
