package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Delivers daily-report notifications to exactly one explicitly configured
 * pilot hotel. Historical notifications before the activation timestamp are
 * ignored, and the existing notification_delivery queue provides idempotency,
 * locking and retry state.
 */
@Component
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.wecom.group-robot.delivery-enabled"}, havingValue = "true")
public class WeComGroupRobotDeliveryWorker {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final TransactionTemplate transactions;
    private final WeComGroupRobotWebhookService destinations;
    private final WeComGroupRobotClient client;
    private final WeComProperties properties;
    private final UUID pilotHotelId;
    private final OffsetDateTime deliveryNotBefore;
    private final int batchSize;
    private final String workerId = "wecom-group-" + UUID.randomUUID();

    public WeComGroupRobotDeliveryWorker(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            TransactionTemplate transactions,
            WeComGroupRobotWebhookService destinations,
            WeComGroupRobotClient client,
            WeComProperties properties,
            @Value("${app.wecom.group-robot.pilot-hotel-id:}") String pilotHotelId,
            @Value("${app.wecom.group-robot.delivery-not-before:}") String deliveryNotBefore,
            @Value("${app.wecom.group-robot.batch-size:20}") int batchSize
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.transactions = transactions;
        this.destinations = destinations;
        this.client = client;
        this.properties = properties;
        this.pilotHotelId = parseUuid(pilotHotelId, "WECOM_GROUP_ROBOT_PILOT_HOTEL_ID");
        this.deliveryNotBefore = parseTimestamp(deliveryNotBefore);
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            fixedDelayString = "${app.wecom.group-robot.fixed-delay-ms:30000}",
            initialDelayString = "${app.wecom.group-robot.initial-delay-ms:30000}"
    )
    public void run() {
        enqueueDailyReportNotifications();
        for (Delivery delivery : claim()) {
            try {
                WeComGroupRobotWebhookService.GroupRobotDestination destination =
                        destinations.resolveForDelivery(properties.tenantId(), pilotHotelId);
                if (!destination.endpointHash().equals(delivery.endpointHash())) {
                    throw new IllegalStateException("WeCom group robot destination binding changed");
                }
                client.sendReportLink(destination.webhook(), delivery.title(), delivery.content(),
                        oauthStartLink(delivery.reportId()));
                markSent(delivery.id());
            } catch (RuntimeException exception) {
                markFailed(delivery.id(), delivery.attemptCount() + 1, exception.getClass().getSimpleName());
            }
        }
    }

    private void enqueueDailyReportNotifications() {
        transactions.executeWithoutResult(status -> {
            prepare();
            List<PendingNotification> rows = jdbc.query("""
                    select notification.id, configuration.webhook_hash
                    from notification
                    join daily_report report
                      on notification.source_type = 'DAILY_REPORT'
                     and report.tenant_id = notification.tenant_id
                     and report.id = notification.source_id
                    join wecom_group_robot_webhook configuration
                      on configuration.tenant_id = report.tenant_id
                     and configuration.hotel_org_unit_id = report.hotel_org_unit_id
                    where notification.tenant_id = :tenantId
                      and report.hotel_org_unit_id = :pilotHotelId
                      and notification.created_at >= :deliveryNotBefore
                      and not exists (
                          select 1 from notification_delivery delivery
                          where delivery.tenant_id = notification.tenant_id
                            and delivery.notification_id = notification.id
                            and delivery.channel = 'WEBHOOK'
                            and delivery.recipient_endpoint_hash = configuration.webhook_hash
                      )
                    order by notification.created_at, notification.id
                    limit :batchSize
                    """, params(), (resultSet, rowNumber) -> new PendingNotification(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("webhook_hash")));
            for (PendingNotification row : rows) {
                jdbc.update("""
                        insert into notification_delivery
                            (tenant_id, notification_id, channel, recipient_endpoint_hash, status)
                        values (:tenantId, :notificationId, 'WEBHOOK', :endpointHash, 'PENDING')
                        on conflict do nothing
                        """, params().addValue("notificationId", row.notificationId())
                        .addValue("endpointHash", row.endpointHash()));
            }
        });
    }

    private List<Delivery> claim() {
        return transactions.execute(status -> {
            prepare();
            List<Delivery> rows = jdbc.query("""
                    select delivery.id, delivery.attempt_count, delivery.recipient_endpoint_hash,
                           notification.title, notification.content, notification.source_id as report_id
                    from notification_delivery delivery
                    join notification
                      on notification.tenant_id = delivery.tenant_id
                     and notification.id = delivery.notification_id
                    join daily_report report
                      on notification.source_type = 'DAILY_REPORT'
                     and report.tenant_id = notification.tenant_id
                     and report.id = notification.source_id
                    join wecom_group_robot_webhook configuration
                      on configuration.tenant_id = report.tenant_id
                     and configuration.hotel_org_unit_id = report.hotel_org_unit_id
                     and configuration.webhook_hash = delivery.recipient_endpoint_hash
                    where delivery.tenant_id = :tenantId
                      and report.hotel_org_unit_id = :pilotHotelId
                      and notification.created_at >= :deliveryNotBefore
                      and delivery.channel = 'WEBHOOK'
                      and delivery.status in ('PENDING', 'FAILED')
                      and delivery.available_at <= now()
                      and (delivery.next_retry_at is null or delivery.next_retry_at <= now())
                      and (delivery.locked_until is null or delivery.locked_until < now())
                    order by delivery.available_at, delivery.created_at, delivery.id
                    for update of delivery skip locked
                    limit :batchSize
                    """, params(), (resultSet, rowNumber) -> new Delivery(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getInt("attempt_count"),
                            resultSet.getString("title"),
                            resultSet.getString("content"),
                            resultSet.getObject("report_id", UUID.class),
                            resultSet.getString("recipient_endpoint_hash")));
            for (Delivery row : rows) {
                jdbc.update("""
                        update notification_delivery
                        set locked_by = :workerId, locked_until = now() + interval '2 minutes'
                        where tenant_id = :tenantId and id = :id
                        """, params().addValue("id", row.id()).addValue("workerId", workerId));
            }
            return rows;
        });
    }

    private void markSent(UUID id) {
        transactions.executeWithoutResult(status -> {
            prepare();
            jdbc.update("""
                    update notification_delivery
                    set status = 'SENT', attempt_count = attempt_count + 1, sent_at = now(),
                        next_retry_at = null, locked_by = null, locked_until = null,
                        last_error = null, row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id and locked_by = :workerId
                    """, params().addValue("id", id).addValue("workerId", workerId));
        });
    }

    private void markFailed(UUID id, int attempts, String errorType) {
        long retryMinutes = Math.min(1440, 1L << Math.min(attempts, 10));
        transactions.executeWithoutResult(status -> {
            prepare();
            jdbc.update("""
                    update notification_delivery
                    set status = 'FAILED', attempt_count = attempt_count + 1, failed_at = now(),
                        next_retry_at = :nextRetryAt, locked_by = null, locked_until = null,
                        last_error = :errorType, row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id and locked_by = :workerId
                    """, params().addValue("id", id).addValue("workerId", workerId)
                    .addValue("nextRetryAt", OffsetDateTime.now().plusMinutes(retryMinutes))
                    .addValue("errorType", errorType));
        });
    }

    private URI oauthStartLink(UUID reportId) {
        URI callback = properties.oauthCallbackUrl();
        return UriComponentsBuilder.newInstance().scheme(callback.getScheme()).host(callback.getHost())
                .port(callback.getPort()).path("/api/v1/integrations/wecom/oauth/start")
                .queryParam("returnTo", "#/daily-reports/" + reportId)
                .build().encode().toUri();
    }

    private void prepare() {
        databaseContext.apply(properties.tenantId());
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenantId", properties.tenantId())
                .addValue("pilotHotelId", pilotHotelId)
                .addValue("deliveryNotBefore", deliveryNotBefore)
                .addValue("batchSize", batchSize);
    }

    private static UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be a UUID when group delivery is enabled");
        }
    }

    private static OffsetDateTime parseTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value == null ? "" : value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "WECOM_GROUP_ROBOT_DELIVERY_NOT_BEFORE must be an ISO-8601 timestamp when group delivery is enabled");
        }
    }

    private record PendingNotification(UUID notificationId, String endpointHash) {
    }

    private record Delivery(UUID id, int attemptCount, String title, String content, UUID reportId,
                            String endpointHash) {
    }
}
