package com.vitallink.readings.threshold;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a request in when its {@code X-Admin-Key} header matches the configured admin key. With no key configured,
 * nothing matches. The key is never logged.
 */
class AdminApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Key";

    private final byte[] expectedHash;

    AdminApiKeyFilter(String adminKey) {
        this.expectedHash = adminKey == null || adminKey.isBlank() ? null : sha256(adminKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        // Comparing hashes with isEqual takes the same time whatever the input, so timing can't reveal the key.
        if (expectedHash != null && given != null && MessageDigest.isEqual(expectedHash, sha256(given))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new PreAuthenticatedAuthenticationToken("admin", null, AuthorityUtils.NO_AUTHORITIES));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
