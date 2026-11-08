package com.vms.identity.auth;

import cn.dev33.satoken.exception.NotLoginException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail invalidCredentials() {
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }

    @ExceptionHandler(NotLoginException.class)
    public ProblemDetail notLoggedIn() {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不合法");
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(code);
        detail.setProperty("code", code);
        return detail;
    }
}
