package cn.sifangguan.hotelaios.integrations.wecom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = {"app.wecom.enabled", "app.wecom.directory-sync-enabled"}, havingValue = "true"
)
public class WeComDirectoryEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(WeComDirectoryEventProcessor.class);
    private final WeComDirectoryEventReceiptStore receipts;
    private final WeComDirectoryOnboardingService onboarding;

    public WeComDirectoryEventProcessor(
            WeComDirectoryEventReceiptStore receipts,
            WeComDirectoryOnboardingService onboarding
    ) {
        this.receipts = receipts;
        this.onboarding = onboarding;
    }

    @Async
    public void processNew(UUID receiptId, UUID correlationId) {
        if (!receipts.begin(receiptId)) return;
        processClaimed(receiptId, correlationId);
    }

    @Scheduled(
            fixedDelayString = "${app.wecom.directory.recovery-delay-ms:15000}",
            initialDelayString = "${app.wecom.directory.recovery-initial-delay-ms:15000}"
    )
    public void recover() {
        for (WeComDirectoryEventReceiptStore.Recovery row : receipts.claimRecoverable(50)) {
            if (row.deadLetter()) {
                onboarding.notifyDeadLetter(row.id(), row.correlationId());
            } else {
                processClaimed(row.id(), row.correlationId());
            }
        }
    }

    private void processClaimed(UUID receiptId, UUID correlationId) {
        try {
            WeComDirectoryEvent event = receipts.load(receiptId);
            boolean handled = onboarding.handleDirectoryEvent(event, receiptId, correlationId);
            receipts.complete(receiptId, handled ? "SUCCEEDED" : "IGNORED");
        } catch (RuntimeException exception) {
            boolean deadLetter = receipts.fail(receiptId, exception);
            if (deadLetter) onboarding.notifyDeadLetter(receiptId, correlationId);
            log.warn("WeCom directory event processing failed; correlationId={}, type={}",
                    correlationId, exception.getClass().getSimpleName());
        }
    }
}
