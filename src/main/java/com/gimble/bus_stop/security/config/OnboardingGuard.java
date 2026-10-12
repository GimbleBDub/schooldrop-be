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

/** Role features stay locked until the account has finished onboarding. */
@Configuration
public class OnboardingGuard implements WebMvcConfigurer, HandlerInterceptor {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/parent/**", "/v1/driver/**", "/v1/school/**");
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user && !user.isOnboarded()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "Finish setting up your account first");
        }
        return true;
    }
}
