package com.vitallink.readings.device;

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

/** Device endpoints: every request must carry a valid device API key. */
@Configuration(proxyBeanMethods = false)
class DeviceSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain deviceEndpoints(HttpSecurity http, DeviceApiKeys keys) {
        return http.securityMatcher("/v1/readings/**")
                // Devices send a key with every request: no cookies or sessions, so CSRF protection does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Created here, not as a bean, so Spring Boot does not also run it for every other request.
                .addFilterBefore(new DeviceApiKeyFilter(keys), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
