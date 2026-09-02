package cn.sifangguan.hotelaios.integrations.ota;

import org.springframework.http.HttpStatus;

final class OtaAuthorizationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    OtaAuthorizationException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    static OtaAuthorizationException badRequest(String code, String message) {
        return new OtaAuthorizationException(HttpStatus.BAD_REQUEST, code, message);
    }

    static OtaAuthorizationException notFound(String code, String message) {
        return new OtaAuthorizationException(HttpStatus.NOT_FOUND, code, message);
    }

    static OtaAuthorizationException conflict(String code, String message) {
        return new OtaAuthorizationException(HttpStatus.CONFLICT, code, message);
    }

    static OtaAuthorizationException badGateway(String code, String message) {
        return new OtaAuthorizationException(HttpStatus.BAD_GATEWAY, code, message);
    }

    static OtaAuthorizationException unavailable(String code, String message) {
        return new OtaAuthorizationException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
