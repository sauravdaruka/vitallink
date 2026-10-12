package com.vitallink.readings.threshold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Threshold admin endpoints: every request must carry the admin key from {@code ADMIN_API_KEY}. In production this
 * would be staff single sign-on instead.
 */
@Configuration(proxyBeanMethods = false)
class AdminSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfig.class);

    @Bean
    @Order(2)
    SecurityFilterChain thresholdAdminEndpoints(HttpSecurity http, @Value("${admin.api-key}") String adminKey) {
        if (adminKey.isBlank()) {
            log.warn("ADMIN_API_KEY is not set, so the threshold admin endpoints reject every request");
        }
        return http.securityMatcher("/v1/admin/thresholds/**")
                // A key is sent with every request: no cookies or sessions, so CSRF protection does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Created here, not as a bean, so Spring Boot does not also run it for every other request.
                .addFilterBefore(new AdminApiKeyFilter(adminKey), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
