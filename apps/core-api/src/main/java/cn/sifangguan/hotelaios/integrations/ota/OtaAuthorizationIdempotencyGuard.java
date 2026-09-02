package cn.sifangguan.hotelaios.integrations.ota;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class OtaAuthorizationIdempotencyGuard {
    private static final int MAX_ENTRIES = 256;
    private static final Duration TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Autowired
    public OtaAuthorizationIdempotencyGuard() {
        this(Clock.systemUTC());
    }

    OtaAuthorizationIdempotencyGuard(Clock clock) {
        this.clock = clock;
    }

    OtaAuthorizationModels.StartResponse execute(
            TenantPrincipal principal,
            OtaAuthorizationModels.StartRequest request,
            Supplier<OtaAuthorizationModels.StartResponse> operation
    ) {
        String scopeHash = hashParts(
                principal.tenantId().toString(), principal.actorId().toString(), request.idempotencyKey());
        String requestHash = hashParts(
                request.hotelId().toString(), request.platformCode(), request.action(),
                request.reason(), request.idempotencyKey());
        Entry entry;
        boolean owner = false;
        synchronized (this) {
            Instant now = clock.instant();
            Iterator<Entry> values = entries.values().iterator();
            while (values.hasNext()) {
                Entry value = values.next();
                if (value.future().isDone() && !value.expiresAt().isAfter(now)) values.remove();
            }
            entry = entries.get(scopeHash);
            if (entry != null && !entry.requestHash().equals(requestHash)) {
                throw OtaAuthorizationException.conflict(
                        "OTA_AUTHORIZATION_IDEMPOTENCY_CONFLICT",
                        "相同幂等键已用于不同的携程授权请求");
            }
            if (entry == null) {
                if (entries.size() >= MAX_ENTRIES) {
                    throw OtaAuthorizationException.unavailable(
                            "OTA_AUTHORIZATION_IDEMPOTENCY_CAPACITY_EXCEEDED",
                            "携程授权请求较多，请稍后重试");
                }
                entry = new Entry(requestHash, now.plus(TTL), new CompletableFuture<>());
                entries.put(scopeHash, entry);
                scheduleExpiry(scopeHash, entry);
                owner = true;
            }
        }

        if (owner) {
            try {
                entry.future().complete(operation.get());
            } catch (RuntimeException exception) {
                entry.future().completeExceptionally(exception);
                remove(scopeHash, entry);
                throw exception;
            } catch (Error error) {
                entry.future().completeExceptionally(error);
                remove(scopeHash, entry);
                throw error;
            }
        }
        try {
            return entry.future().join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw OtaAuthorizationException.unavailable(
                    "OTA_AUTHORIZATION_IDEMPOTENCY_REPLAY_FAILED", "携程授权请求重放失败");
        }
    }

    private void scheduleExpiry(String scopeHash, Entry entry) {
        CompletableFuture.delayedExecutor(TTL.toMillis(), TimeUnit.MILLISECONDS).execute(
                () -> remove(scopeHash, entry));
    }

    private synchronized void remove(String scopeHash, Entry expected) {
        entries.remove(scopeHash, expected);
    }

    private static String hashParts(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用");
        }
    }

    private record Entry(
            String requestHash,
            Instant expiresAt,
            CompletableFuture<OtaAuthorizationModels.StartResponse> future
    ) {
    }
}
