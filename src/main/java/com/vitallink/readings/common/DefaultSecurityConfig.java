package com.vitallink.readings.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Everything not claimed by a feature's own security rules is denied, so a new endpoint is never open by mistake. */
@Configuration(proxyBeanMethods = false)
class DefaultSecurityConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain everythingElse(HttpSecurity http) {
        return http.authorizeHttpRequests(requests -> requests
                        // Open for local use. In production, actuator runs on a separate port that is not public.
                        .requestMatchers("/actuator/**")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
