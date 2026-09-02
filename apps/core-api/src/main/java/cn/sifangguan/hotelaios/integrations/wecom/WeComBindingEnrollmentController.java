package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComUserBindingModels.*;

@RestController
@RequestMapping("/api/v1/integrations/wecom/binding-enrollment")
@ConditionalOnProperty(name = {"app.wecom.enabled", "app.security.local-login.enabled"}, havingValue = "true")
public class WeComBindingEnrollmentController {
    private final WeComBindingEnrollmentService service;

    public WeComBindingEnrollmentController(WeComBindingEnrollmentService service) { this.service = service; }

    @PostMapping("/preview")
    public ResponseEntity<EnrollmentPreview> preview(@Valid @RequestBody EnrollmentTokenRequest request) {
        return noStore(ResponseEntity.ok()).body(service.preview(request.token()));
    }

    @PostMapping("/start")
    public ResponseEntity<EnrollmentAuthorization> start(@Valid @RequestBody EnrollmentTokenRequest request) {
        WeComBindingEnrollmentService.Start start = service.start(request.token());
        ResponseCookie cookie = ResponseCookie.from(WeComOAuthController.BINDING_VERIFIER_COOKIE,
                        start.browserVerifier())
                .httpOnly(true).secure(true).sameSite("Lax").path("/")
                .maxAge(start.maxAgeSeconds()).build();
        return noStore(ResponseEntity.ok())
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new EnrollmentAuthorization(start.authorizationUri()));
    }

    private static <T extends ResponseEntity.HeadersBuilder<T>> T noStore(T builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff");
    }
}
