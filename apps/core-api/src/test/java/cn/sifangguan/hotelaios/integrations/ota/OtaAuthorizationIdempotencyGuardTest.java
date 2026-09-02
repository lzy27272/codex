package cn.sifangguan.hotelaios.integrations.ota;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtaAuthorizationIdempotencyGuardTest {
    private final TenantPrincipal principal = new TenantPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), "OTA_OPERATION_MANAGER",
            Set.of(UUID.randomUUID()), UUID.randomUUID());
    private final OtaAuthorizationIdempotencyGuard guard = new OtaAuthorizationIdempotencyGuard(
            Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void concurrentIdenticalRequestsExecuteTheOperationOnlyOnce() throws Exception {
        OtaAuthorizationModels.StartRequest request = request("同一请求", "concurrent-key-002");
        OtaAuthorizationModels.StartResponse expected = response();
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.function.Supplier<OtaAuthorizationModels.StartResponse> operation = () -> {
                calls.incrementAndGet();
                operationStarted.countDown();
                try {
                    if (!releaseOperation.await(2, TimeUnit.SECONDS)) throw new AssertionError("test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("test interrupted");
                }
                return expected;
            };
            Future<OtaAuthorizationModels.StartResponse> first = executor.submit(
                    () -> guard.execute(principal, request, operation));
            assertThat(operationStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<OtaAuthorizationModels.StartResponse> second = executor.submit(
                    () -> guard.execute(principal, request, operation));
            releaseOperation.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(expected);
            assertThat(second.get(2, TimeUnit.SECONDS)).isSameAs(expected);
            assertThat(calls).hasValue(1);
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sameKeyWithDifferentPayloadConflictsWithoutSecondOperation() {
        AtomicInteger calls = new AtomicInteger();
        OtaAuthorizationModels.StartRequest first = request("原因一", "same-key-002");
        OtaAuthorizationModels.StartRequest changed = request("原因二", "same-key-002");
        guard.execute(principal, first, () -> {
            calls.incrementAndGet();
            return response();
        });

        assertThatThrownBy(() -> guard.execute(principal, changed, () -> {
            calls.incrementAndGet();
            return response();
        })).isInstanceOfSatisfying(OtaAuthorizationException.class,
                exception -> assertThat(exception.code()).isEqualTo("OTA_AUTHORIZATION_IDEMPOTENCY_CONFLICT"));
        assertThat(calls).hasValue(1);
    }

    private static OtaAuthorizationModels.StartRequest request(String reason, String key) {
        return new OtaAuthorizationModels.StartRequest(
                OtaPilotScope.HOTEL_ID, "CTRIP", "DISCOVERY", reason, key);
    }

    private static OtaAuthorizationModels.StartResponse response() {
        return new OtaAuthorizationModels.StartResponse(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "WAITING_FOR_USER", true,
                OtaPilotScope.AUTHORIZATION_URL_PREFIX + "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
                null, OtaAuthorizationModels.BindingStatus.DISCOVERY_REQUIRED,
                OtaAuthorizationModels.SessionStatus.REAUTH_REQUIRED);
    }
}
