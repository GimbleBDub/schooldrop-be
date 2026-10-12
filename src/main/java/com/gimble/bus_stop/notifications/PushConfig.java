package com.gimble.bus_stop.notifications;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default PushSender until Firebase credentials are configured: logs instead of sending.
 * In-app notifications (the inbox) still work without it.
 */
@Configuration
@Slf4j
public class PushConfig {

    @Bean
    @ConditionalOnMissingBean(PushSender.class)
    PushSender pushSender() {
        return (tokens, title, body, data) -> {
            if (!tokens.isEmpty()) {
                log.info("[push stub] to {} device(s): {} — {} {}", tokens.size(), title, body, data);
            }
        };
    }
}
