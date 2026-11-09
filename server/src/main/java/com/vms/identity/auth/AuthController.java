package com.vms.identity.auth;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpUtil;
import com.vms.identity.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;
    private final SaTokenConfig saTokenConfig;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter,
                          SaTokenConfig saTokenConfig) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
        this.saTokenConfig = saTokenConfig;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String sourceIp = httpRequest.getRemoteAddr();
        UserAccount user;
        try (var permit = loginRateLimiter.acquire(sourceIp)) {
            try {
                user = authService.authenticate(request.username(), request.password(), sourceIp);
            } catch (InvalidCredentialsException e) {
                permit.failed();
                throw e;
            }
        }

        authService.openSession(user);
        String token = StpUtil.getTokenValue();
        try {
            authService.auditLoginSuccess(user, sourceIp);
        } catch (RuntimeException e) {
            // 审计不可用时不得留下无记录的成功会话
            StpUtil.logout();
            throw e;
        }
        return new LoginResponse(token, user.getUsername(), user.getRoleCode(), saTokenConfig.getTimeout());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest) {
        var user = authService.currentUser();
        StpUtil.logout();
        authService.auditLogout(user, httpRequest.getRemoteAddr());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changeOwnPassword(authService.currentUser(), request.oldPassword(), request.newPassword());
        StpUtil.logout();
    }

    @GetMapping("/me")
    public MeResponse me() {
        var user = authService.currentUser();
        return new MeResponse(user.getId(), user.getUsername(), user.getRoleCode());
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 200) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 200) String oldPassword,
            @NotBlank @Size(min = 8, max = 200) String newPassword) {
    }

    public record LoginResponse(String token, String username, String roleCode, long expiresIn) {
    }

    public record MeResponse(Long id, String username, String roleCode) {
    }
}
