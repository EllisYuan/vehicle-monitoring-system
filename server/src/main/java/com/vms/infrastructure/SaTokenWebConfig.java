package com.vms.infrastructure;

import com.vms.identity.security.ApiAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenWebConfig implements WebMvcConfigurer {

    private final ApiAuthorizationInterceptor authorizationInterceptor;

    public SaTokenWebConfig(ApiAuthorizationInterceptor authorizationInterceptor) {
        this.authorizationInterceptor = authorizationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 所有 /api/v1 接口除登录外都经过登录、会话与功能权限检查
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login");
    }
}
