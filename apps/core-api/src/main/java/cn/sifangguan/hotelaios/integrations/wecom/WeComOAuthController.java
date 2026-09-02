package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/integrations/wecom/oauth")
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.security.local-login.enabled"}, havingValue = "true")
public class WeComOAuthController {
    static final String VERIFIER_COOKIE = "__Host-wecom_oauth_verifier";
    static final String BINDING_VERIFIER_COOKIE = "__Host-wecom_binding_verifier";
    private final WeComOAuthService service;
    private final WeComBindingEnrollmentService enrollmentService;

    public WeComOAuthController(
            WeComOAuthService service, WeComBindingEnrollmentService enrollmentService
    ) {
        this.service = service;
        this.enrollmentService = enrollmentService;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam(required = false) String returnTo) {
        WeComOAuthService.Start start = service.start(returnTo);
        return noStore(ResponseEntity.status(HttpStatus.FOUND))
                .header(HttpHeaders.LOCATION, start.authorizationUri().toString())
                .header(HttpHeaders.SET_COOKIE, verifierCookie(start.browserVerifier(), start.maxAgeSeconds()).toString())
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String code,
            @RequestParam String state,
            @CookieValue(name = VERIFIER_COOKIE, required = false) String browserVerifier,
            @CookieValue(name = BINDING_VERIFIER_COOKIE, required = false) String bindingBrowserVerifier
    ) {
        try {
            boolean taskFlow = browserVerifier != null && !browserVerifier.isBlank();
            boolean bindingFlow = bindingBrowserVerifier != null && !bindingBrowserVerifier.isBlank();
            if (taskFlow == bindingFlow) {
                throw new IllegalArgumentException("企业微信OAuth浏览器状态无效");
            }
            URI location = taskFlow
                    ? service.callback(code, state, browserVerifier)
                    : enrollmentService.callback(code, state, bindingBrowserVerifier);
            return noStore(ResponseEntity.status(HttpStatus.FOUND))
                    .header(HttpHeaders.LOCATION, location.toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(VERIFIER_COOKIE).toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString())
                    .build();
        } catch (RuntimeException exception) {
            return noStore(ResponseEntity.status(HttpStatus.UNAUTHORIZED))
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(VERIFIER_COOKIE).toString())
                    .header(HttpHeaders.SET_COOKIE, clearVerifierCookie(BINDING_VERIFIER_COOKIE).toString())
                    .build();
        }
    }

    @PostMapping("/exchange")
    public ResponseEntity<WeComOAuthModels.ExchangeResponse> exchange(
            @Valid @RequestBody WeComOAuthModels.ExchangeRequest request
    ) {
        return noStore(ResponseEntity.ok()).body(service.exchange(request.exchangeCode()));
    }

    private static ResponseCookie verifierCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(VERIFIER_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(maxAgeSeconds).build();
    }

    private static ResponseCookie clearVerifierCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(0).build();
    }

    private static <T extends ResponseEntity.HeadersBuilder<T>> T noStore(T builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer");
    }
}
