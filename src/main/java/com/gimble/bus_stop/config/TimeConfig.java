package com.gimble.bus_stop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/**
 * "Today", school days and trip dates are all in Botswana time, whatever the server's zone is.
 * Inject Clock (instead of calling now()) so tests can pin the date.
 */
@Configuration
@EnableScheduling
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${app.timezone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
