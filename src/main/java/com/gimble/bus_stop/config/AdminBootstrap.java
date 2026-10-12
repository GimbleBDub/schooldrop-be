package com.gimble.bus_stop.config;

import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.Role;
import com.gimble.bus_stop.enums.StaffPermission;
import com.gimble.bus_stop.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Creates the first platform admin from config so there is a way into the admin panel.
 * Does nothing once any ADMIN exists — further staff are created from the admin panel.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.bootstrap-email:}")
    private String email;

    @Value("${app.admin.bootstrap-password:}")
    private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank() || userRepository.existsByRole(Role.ADMIN)) {
            return;
        }

        userRepository.save(User.builder()
                .firstName("Platform")
                .lastName("Admin")
                .email(email.toLowerCase().trim())
                .password(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .onboardedAt(Instant.now())
                .staffPermissions(new HashSet<>(Set.of(StaffPermission.SUPER_ADMIN)))
                .emailVerified(true)
                .build());
        log.warn("Bootstrap admin account created: {}. Change its password.", email);
    }
}
