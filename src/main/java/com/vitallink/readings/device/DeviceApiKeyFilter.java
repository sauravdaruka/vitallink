package com.vitallink.readings.device;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identifies the device from the {@code X-Device-Key} header. A missing or unknown key leaves the request
 * unauthenticated, and Spring Security then answers 401. The key itself is never logged.
 */
class DeviceApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Device-Key";

    private final DeviceApiKeys keys;

    DeviceApiKeyFilter(DeviceApiKeys keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            keys.findDevice(apiKey).ifPresent(device -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        new PreAuthenticatedAuthenticationToken(device, null, AuthorityUtils.NO_AUTHORITIES));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
