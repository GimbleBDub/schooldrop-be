package com.gimble.bus_stop.security.config;

import com.gimble.bus_stop.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final AuthRateLimitFilter authRateLimitFilter;

    // Web origins (admin panel, school portal, parent web). Patterns like http://localhost:* are allowed.
    // The mobile apps don't need CORS.
    @Value("${app.cors.allowed-origin-patterns}")
    private List<String> allowedOriginPatterns;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // Public: sign-up/in, kid pairing (the one-time code is the credential), payment webhook
                        .requestMatchers(
                                "/v1/auth/register",
                                "/v1/auth/login",
                                "/v1/auth/refresh",
                                "/v1/auth/verify-email",
                                "/v1/auth/kid/pair",
                                "/v1/payments/webhook",
                                // Signed, expiring PDF links (the controller checks the token or the signature)
                                "/v1/billing/invoices/*/pdf",
                                // Liveness/readiness for Docker and load balancers
                                "/actuator/health/**",
                                // API docs — disabled in production via API_DOCS_ENABLED=false
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // One area per role
                        .requestMatchers("/v1/parent/**").hasRole("PARENT")
                        .requestMatchers("/v1/driver/**").hasRole("DRIVER")
                        .requestMatchers("/v1/school/**").hasRole("SCHOOL")
                        .requestMatchers("/v1/kid/**").hasRole("KID")

                        // Back-office (admin panel) — platform staff only; finer permissions via @PreAuthorize
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")

                        // Everything else requires auth
                        .anyRequest().authenticated()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .exceptionHandling(ex -> ex
                        // 401 tells the app to refresh its access token; 403 means "not allowed"
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, HttpStatus.FORBIDDEN, "You do not have permission to do this"))
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    private static void writeError(HttpServletResponse response,
                                   HttpStatus status,
                                   String message) throws IOException {
        // Same shape as ApiResponse.error(...)
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"success\":false,\"error\":\"" + message + "\",\"timestamp\":\"" + LocalDateTime.now() + "\"}"
        );
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
