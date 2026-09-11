package cn.sifangguan.hotelaios.shared.features;

import org.springframework.http.HttpStatus;

/** Stable RFC 9457 error used by the gated group-management APIs. */
public final class GroupManagementException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public GroupManagementException(HttpStatus status, String code, String message) {
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

    public static GroupManagementException featureNotEnabled() {
        return new GroupManagementException(HttpStatus.NOT_FOUND, "FEATURE_NOT_ENABLED", "当前租户未启用该功能");
    }

    public static GroupManagementException notFound() {
        return new GroupManagementException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在或不在当前可见范围");
    }

    public static GroupManagementException forbidden(String code, String message) {
        return new GroupManagementException(HttpStatus.FORBIDDEN, code, message);
    }

    public static GroupManagementException conflict(String code, String message) {
        return new GroupManagementException(HttpStatus.CONFLICT, code, message);
    }
}
