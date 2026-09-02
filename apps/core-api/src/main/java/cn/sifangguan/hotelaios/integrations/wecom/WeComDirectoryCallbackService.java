package cn.sifangguan.hotelaios.integrations.wecom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = {"app.wecom.enabled", "app.wecom.directory-sync-enabled"}, havingValue = "true"
)
public class WeComDirectoryCallbackService {
    private final WeComDirectoryProperties properties;
    private final WeComDirectoryCallbackCrypto crypto;
    private final WeComDirectoryXml xml;
    private final WeComDirectoryEventReceiptStore receipts;
    private final WeComDirectoryEventProcessor processor;

    public WeComDirectoryCallbackService(
            WeComDirectoryProperties properties,
            WeComDirectoryCallbackCrypto crypto,
            WeComDirectoryXml xml,
            WeComDirectoryEventReceiptStore receipts,
            WeComDirectoryEventProcessor processor
    ) {
        this.properties = properties;
        this.crypto = crypto;
        this.xml = xml;
        this.receipts = receipts;
        this.processor = processor;
    }

    public String verify(String signature, String timestamp, String nonce, String echo) {
        validateTimestamp(timestamp);
        return crypto.verifyAndDecrypt(signature, timestamp, nonce, echo);
    }

    public String receive(String signature, String timestamp, String nonce, String encryptedEnvelope) {
        validateTimestamp(timestamp);
        String encrypted = xml.encryptedEnvelope(encryptedEnvelope);
        String plaintext = crypto.verifyAndDecrypt(signature, timestamp, nonce, encrypted);
        WeComDirectoryEvent event = xml.parseEvent(plaintext);
        UUID correlationId = UUID.randomUUID();
        WeComDirectoryEventReceiptStore.Reservation receipt = receipts.reserve(event, correlationId);
        if (!receipt.duplicate()) processor.processNew(receipt.id(), correlationId);
        return "success";
    }

    private void validateTimestamp(String raw) {
        long epoch;
        try { epoch = Long.parseLong(raw); }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("WeCom directory callback timestamp is invalid");
        }
        Duration drift = Duration.between(Instant.ofEpochSecond(epoch), Instant.now()).abs();
        if (drift.compareTo(properties.callbackClockSkew()) > 0) {
            throw new IllegalArgumentException("WeCom directory callback timestamp is outside the allowed window");
        }
    }
}

