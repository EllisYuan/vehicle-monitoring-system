package com.vms.infrastructure.web;

import cn.dev33.satoken.exception.NotLoginException;
import com.vms.identity.auth.InvalidCredentialsException;
import com.vms.identity.auth.LoginRateLimitedException;
import com.vms.identity.auth.SessionRevokedException;
import com.vms.identity.security.ForbiddenException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail invalidCredentials() {
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }

    @ExceptionHandler(SessionRevokedException.class)
    public ProblemDetail sessionRevoked(SessionRevokedException e) {
        return problem(HttpStatus.UNAUTHORIZED, e.code(), "会话已失效，请重新登录");
    }

    @ExceptionHandler(NotLoginException.class)
    public ProblemDetail notLoggedIn(NotLoginException e) {
        // 旧会话被新登录替换或主动下线时给出可识别的明确提示
        String type = e.getType();
        if (NotLoginException.BE_REPLACED.equals(type) || NotLoginException.KICK_OUT.equals(type)) {
            return problem(HttpStatus.UNAUTHORIZED, "SESSION_REPLACED", "账号已在其他位置登录，当前会话已失效");
        }
        if (NotLoginException.TOKEN_TIMEOUT.equals(type) || NotLoginException.INVALID_TOKEN.equals(type)) {
            // 到期与存储侧会话丢失（如 Redis 重启）都要求重新登录
            return problem(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录已过期或已失效，请重新登录");
        }
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    }

    @ExceptionHandler(LoginRateLimitedException.class)
    public ResponseEntity<ProblemDetail> rateLimited(LoginRateLimitedException e) {
        ProblemDetail detail = problem(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "登录尝试过于频繁，请稍后再试");
        detail.setProperty("retryAfter", e.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(detail);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail forbidden() {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有权限访问该资源");
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.code(), e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail badRequest(BadRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ProblemDetail concurrentChange() {
        // 死锁与锁等待超时等并发冲突统一为可重试的 409，不暴露内部 SQL 细节
        return problem(HttpStatus.CONFLICT, "CONCURRENT_CHANGE", "操作与并发修改冲突，请刷新后重试");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不合法");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableRequest() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不合法");
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(code);
        detail.setProperty("code", code);
        return detail;
    }
}
