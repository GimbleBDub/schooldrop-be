package com.gimble.bus_stop.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slows down password guessing and pairing-code guessing: at most N attempts per client IP per minute
 * on the credential endpoints. In-memory (fine for one instance; use Redis or the gateway when scaling out).
 * Behind a proxy, configure server.forward-headers-strategy so getRemoteAddr() is the real client.
 */
@Component
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED = Set.of(
            "/v1/auth/login", "/v1/auth/kid/pair", "/v1/auth/refresh",
            "/v1/auth/register",
            // Password-checked account changes: stop someone at an unlocked device guessing the password
            "/v1/account/email", "/v1/account/phone", "/v1/account/password");
    private static final long WINDOW_MS = 60_000;

    private final int maxPerWindow;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(@Value("${app.security.auth-attempts-per-minute:20}") int maxPerWindow) {
        this.maxPerWindow = maxPerWindow;
    }

    private record Window(long startedAt, AtomicInteger count) {
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String key = request.getRemoteAddr() + "|" + request.getServletPath();
        Window window = windows.compute(key, (k, w) ->
                w == null || now - w.startedAt() > WINDOW_MS ? new Window(now, new AtomicInteger()) : w);
        if (window.count().incrementAndGet() > maxPerWindow) {
            log.warn("Rate limited {} on {}", request.getRemoteAddr(), request.getServletPath());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf((WINDOW_MS - (now - window.startedAt())) / 1000 + 1));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"success\":false,\"error\":\"Too many attempts. Try again in a minute.\"}");
            return;
        }
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startedAt() > WINDOW_MS);
        }
        chain.doFilter(request, response);
    }
}
