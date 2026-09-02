package cn.sifangguan.hotelaios.integrations.ota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/ota/connector-authorizations")
public class OtaConnectorAuthorizationController {
    private final OtaConnectorAuthorizationService service;

    public OtaConnectorAuthorizationController(OtaConnectorAuthorizationService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<OtaAuthorizationModels.StatusResponse> status(
            @RequestParam UUID hotelId,
            @RequestParam @NotBlank String platformCode
    ) {
        return noStore(HttpStatus.OK, service.status(hotelId, platformCode));
    }

    @PostMapping("/actions/start")
    ResponseEntity<OtaAuthorizationModels.StartResponse> start(
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @Valid @RequestBody OtaAuthorizationModels.StartRequest request
    ) {
        if (!idempotencyKey.equals(request.idempotencyKey())) {
            throw OtaAuthorizationException.badRequest(
                    "OTA_AUTHORIZATION_IDEMPOTENCY_HEADER_MISMATCH",
                    "Idempotency-Key请求头必须与请求体完全一致");
        }
        return noStore(HttpStatus.ACCEPTED, service.start(request, idempotencyKey));
    }

    @GetMapping("/credentials")
    ResponseEntity<OtaAuthorizationModels.CredentialStatusResponse> credentialStatus(
            @RequestParam UUID hotelId,
            @RequestParam @NotBlank String platformCode
    ) {
        return noStore(HttpStatus.OK, service.credentialStatus(hotelId, platformCode));
    }

    @PutMapping("/credentials")
    ResponseEntity<OtaAuthorizationModels.CredentialStatusResponse> saveCredentials(
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @Valid @RequestBody OtaAuthorizationModels.CredentialSaveRequest request
    ) {
        try {
            return noStore(HttpStatus.OK, service.saveCredentials(request, idempotencyKey));
        } finally {
            request.clearSecrets();
        }
    }

    @PostMapping("/credentials/actions/login")
    ResponseEntity<OtaAuthorizationModels.CredentialChallengeResponse> startCredentialLogin(
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @Valid @RequestBody OtaAuthorizationModels.CredentialLoginRequest request
    ) {
        return noStore(HttpStatus.ACCEPTED, service.startCredentialLogin(request, idempotencyKey));
    }

    @GetMapping("/credentials/challenges/{challengeId}")
    ResponseEntity<OtaAuthorizationModels.CredentialChallengeResponse> credentialChallenge(
            @PathVariable UUID challengeId,
            @RequestParam UUID hotelId,
            @RequestParam @NotBlank String platformCode
    ) {
        return noStore(
                HttpStatus.OK,
                service.credentialChallenge(hotelId, platformCode, challengeId));
    }

    @PostMapping("/credentials/challenges/{challengeId}/actions/send-code")
    ResponseEntity<OtaAuthorizationModels.CredentialChallengeResponse> sendCredentialCode(
            @PathVariable UUID challengeId,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @Valid @RequestBody OtaAuthorizationModels.CredentialLoginRequest request
    ) {
        return noStore(
                HttpStatus.ACCEPTED,
                service.sendCredentialCode(request, challengeId, idempotencyKey));
    }

    @PostMapping("/credentials/challenges/{challengeId}/actions/submit-code")
    ResponseEntity<OtaAuthorizationModels.CredentialChallengeResponse> submitCredentialCode(
            @PathVariable UUID challengeId,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @Valid @RequestBody OtaAuthorizationModels.CredentialCodeRequest request
    ) {
        if (!challengeId.equals(request.challengeId())) {
            throw OtaAuthorizationException.badRequest(
                    "OTA_AUTHORIZATION_CHALLENGE_MISMATCH", "验证码请求与登录任务不匹配");
        }
        try {
            return noStore(
                    HttpStatus.ACCEPTED,
                    service.submitCredentialCode(request, idempotencyKey));
        } finally {
            request.clearSecret();
        }
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }
}
