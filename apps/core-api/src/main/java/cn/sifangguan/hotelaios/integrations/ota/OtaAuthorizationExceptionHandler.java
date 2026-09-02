package cn.sifangguan.hotelaios.integrations.ota;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OtaConnectorAuthorizationController.class)
public final class OtaAuthorizationExceptionHandler {
    @ExceptionHandler(OtaAuthorizationException.class)
    ProblemDetail authorizationFailure(OtaAuthorizationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setTitle("OTA授权操作失败");
        problem.setProperty("code", exception.code());
        return problem;
    }
}
