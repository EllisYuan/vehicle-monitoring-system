package com.vms.identity.security;

import cn.dev33.satoken.stp.StpUtil;
import com.vms.identity.auth.AuthService;
import com.vms.identity.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 所有业务接口先校验登录，再按最新账号状态/授权版本刷新会话有效性；
 * 标注 {@link RequiresPermission} 的接口最后执行功能权限检查。
 * 需要组合权限或拒绝时写审计的接口（如指令下发与指令记录读取）由控制器显式检查，
 * 不标注该注解。
 */
@Component
public class ApiAuthorizationInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public ApiAuthorizationInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        StpUtil.checkLogin();
        UserAccount user = authService.requireSession();
        if (handler instanceof HandlerMethod handlerMethod) {
            RequiresPermission annotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
            if (annotation == null) {
                annotation = handlerMethod.getBeanType().getAnnotation(RequiresPermission.class);
            }
            if (annotation != null) {
                authService.requirePermission(user, annotation.value());
            }
        }
        return true;
    }
}
