package cn.sifangguan.hotelaios.integrations.wecom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComUserBindingModels.EnrollmentPreview;

@Service
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.security.local-login.enabled"}, havingValue = "true")
public class WeComBindingEnrollmentService {
    private static final int MAX_SECRET_LENGTH = 512;
    private final SecureRandom secureRandom = new SecureRandom();
    private final WeComProperties properties;
    private final WeComApiClient apiClient;
    private final WeComBindingEnrollmentStore store;

    public WeComBindingEnrollmentService(
            WeComProperties properties, WeComApiClient apiClient, WeComBindingEnrollmentStore store
    ) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.store = store;
    }

    public EnrollmentPreview preview(String token) {
        String rawToken = bounded(token, "绑定邀请令牌");
        WeComBindingEnrollmentStore.PreviewRecord preview = store.preview(sha256(rawToken));
        OffsetDateTime now = OffsetDateTime.now();
        boolean canStart = "WAITING_SCAN".equals(preview.status()) && preview.expiresAt().isAfter(now);
        boolean expiringSoon = canStart && preview.expiresAt().isBefore(now.plusMinutes(30));
        String message = switch (preview.status()) {
            case "WAITING_SCAN" -> expiringSoon ? "邀请即将过期，请立即完成确认" : "请核对本人信息后使用企业微信确认身份";
            case "AUTHORIZING" -> "该邀请正在企业微信验证中，请勿重复提交";
            case "PENDING_APPROVAL" -> "身份验证已完成，等待管理员确认启用";
            case "CONFLICT" -> "身份存在冲突，等待管理员核对审批";
            case "EXPIRED" -> "邀请已过期，请联系管理员重新生成";
            case "CANCELLED" -> "邀请已取消";
            case "REJECTED" -> "绑定未通过，请联系管理员";
            case "APPROVED" -> "绑定已启用";
            default -> "当前邀请不可继续，请联系管理员处理";
        };
        return new EnrollmentPreview(preview.employeeName(), preview.hotelName(), preview.departmentName(),
                preview.positionName(), preview.expiresAt(), preview.status(), expiringSoon, canStart, message);
    }

    public Start start(String token) {
        String rawToken = bounded(token, "绑定邀请令牌");
        String state = randomSecret();
        String verifier = randomSecret();
        store.start(sha256(rawToken), sha256(state), sha256(verifier));
        URI authorizationUri = UriComponentsBuilder.fromUriString("https://open.weixin.qq.com/connect/oauth2/authorize")
                .queryParam("appid", properties.corpId())
                .queryParam("redirect_uri", properties.oauthCallbackUrl().toString())
                .queryParam("response_type", "code")
                .queryParam("scope", "snsapi_base")
                .queryParam("agentid", properties.agentId())
                .queryParam("state", state)
                .fragment("wechat_redirect")
                .build().encode().toUri();
        return new Start(authorizationUri, verifier, properties.stateTtl().toSeconds());
    }

    public URI callback(String providerCode, String state, String browserVerifier) {
        String code = bounded(providerCode, "企业微信授权码");
        String rawState = bounded(state, "企业微信OAuth状态");
        String verifier = bounded(browserVerifier, "浏览器验证信息");
        UUID requestId = store.claim(sha256(rawState), sha256(verifier), sha256(code));
        try {
            String wecomUserId = bounded(apiClient.exchangeOAuthCode(code), "企业微信UserID");
            String fingerprint = sha256(properties.corpId() + ":" + wecomUserId);
            WeComBindingEnrollmentStore.Completion completion = store.complete(requestId, wecomUserId, fingerprint);
            return resultUri(completion.status());
        } catch (RuntimeException exception) {
            store.fail(requestId, failureCode(exception));
            throw exception;
        }
    }

    private URI resultUri(String status) {
        String base = properties.frontendBaseUrl().toString().replaceAll("/+$", "");
        String safeStatus = switch (status) {
            case "CONFLICT" -> "conflict";
            case "FAILED" -> "failed";
            case "EXPIRED" -> "expired";
            default -> "pending";
        };
        return URI.create(base + "/#/wecom-binding-result?status=" + safeStatus);
    }

    private static String failureCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
        if (name.contains("TIMEOUT")) return "WECOM_OAUTH_TIMEOUT";
        if (exception instanceof IllegalArgumentException) return "WECOM_OAUTH_INVALID_RESPONSE";
        return "WECOM_OAUTH_TECHNICAL_FAILURE";
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String bounded(String value, String label) {
        if (value == null || value.isBlank() || value.length() > MAX_SECRET_LENGTH) {
            throw new IllegalArgumentException(label + "缺失或长度无效");
        }
        return value.trim();
    }

    private static String sha256(String value) { return WeComUserBindingAdministrationService.sha256(value); }

    public record Start(URI authorizationUri, String browserVerifier, long maxAgeSeconds) { }
}
