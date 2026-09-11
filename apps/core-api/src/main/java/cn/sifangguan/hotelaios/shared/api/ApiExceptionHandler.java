package cn.sifangguan.hotelaios.shared.api;

import cn.sifangguan.hotelaios.shared.context.TenantContext;
import cn.sifangguan.hotelaios.shared.features.GroupManagementException;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.BusinessIdentityException;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({AccessDeniedException.class})
    ProblemDetail forbidden(RuntimeException exception) {
        return problem(HttpStatus.FORBIDDEN, "访问被拒绝", exception.getMessage(), "ACCESS_DENIED");
    }

    @ExceptionHandler(BusinessIdentityException.class)
    ProblemDetail businessIdentity(BusinessIdentityException exception) {
        return problem(exception.status(), "业务身份无效", exception.getMessage(), exception.code());
    }

    @ExceptionHandler(GroupManagementException.class)
    ProblemDetail groupManagement(GroupManagementException exception) {
        return problem(exception.status(), "集团管理请求失败", exception.getMessage(), exception.code());
    }

    @ExceptionHandler(IdentityAuthenticationException.class)
    ProblemDetail unauthorized(IdentityAuthenticationException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "身份认证失败", exception.getMessage(), "AUTHENTICATION_FAILED");
    }

    @ExceptionHandler({EmptyResultDataAccessException.class})
    ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "资源不存在", "目标资源不存在或不属于当前租户", "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    ProblemDetail invalid(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "请求校验失败", exception.getMessage(), "REQUEST_INVALID");
    }

    @ExceptionHandler(TenantContext.MissingTenantContextException.class)
    ProblemDetail context(TenantContext.MissingTenantContextException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "身份上下文缺失", exception.getMessage(), "AUTHENTICATION_FAILED");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
