package com.gimble.bus_stop.security.config;

import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.exceptions.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Staff on a temporary password (new, or reset by a super admin) must choose their own before using the back office. */
@Configuration
public class PasswordChangeGuard implements WebMvcConfigurer, HandlerInterceptor {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/admin/**");
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user && user.isPasswordChangeRequired()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "Choose your own password first (Settings → Password)");
        }
        return true;
    }
}
