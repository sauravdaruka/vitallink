package com.vitallink.readings.query;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The read APIs have no login in this exercise (assumption A15): the brief asks for one authentication scheme,
 * which is the device API key. In production, coaches log in through the identity provider (OAuth2/OIDC) and only
 * see their own patients; see the design document.
 */
@Configuration(proxyBeanMethods = false)
class QuerySecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain readEndpoints(HttpSecurity http) {
        return http.securityMatcher("/v1/patients/**", "/v1/coaches/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
