package cn.sifangguan.hotelaios.shared.security;

import org.springframework.http.HttpStatus;

/** A stable RFC 9457 error for the request's responsibility-bearing assignment. */
public final class BusinessIdentityException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BusinessIdentityException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static BusinessIdentityException required() {
        return new BusinessIdentityException(
                HttpStatus.BAD_REQUEST,
                "BUSINESS_ASSIGNMENT_REQUIRED",
                "当前操作需要通过X-Assignment-Id选择业务任职"
        );
    }

    public static BusinessIdentityException invalidFormat() {
        return new BusinessIdentityException(
                HttpStatus.BAD_REQUEST,
                "BUSINESS_ASSIGNMENT_INVALID_FORMAT",
                "X-Assignment-Id不是有效UUID"
        );
    }

    public static BusinessIdentityException forbidden() {
        return new BusinessIdentityException(
                HttpStatus.FORBIDDEN,
                "BUSINESS_ASSIGNMENT_FORBIDDEN",
                "所选任职无效、已暂停、跨租户或不属于当前账号"
        );
    }

    public static BusinessIdentityException mismatch() {
        return new BusinessIdentityException(
                HttpStatus.FORBIDDEN,
                "BUSINESS_ACTOR_MISMATCH",
                "请求中的业务行为人任职与当前身份不一致"
        );
    }

    public static BusinessIdentityException roleMismatch() {
        return new BusinessIdentityException(
                HttpStatus.FORBIDDEN,
                "BUSINESS_ASSIGNMENT_ROLE_MISMATCH",
                "所选任职不具备该业务操作要求的岗位身份"
        );
    }
}
