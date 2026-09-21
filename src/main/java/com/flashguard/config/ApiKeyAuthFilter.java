package com.flashguard.config;

import com.flashguard.entity.Organization;
import com.flashguard.repository.OrganizationRepository;
import com.flashguard.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final OrganizationRepository organizationRepository;
    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(OrganizationRepository organizationRepository, ApiKeyService apiKeyService) {
        this.organizationRepository = organizationRepository;
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Allow CORS preflight requests
        if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Don't require auth on registration endpoint or health checks
        if (path.startsWith("/v1/organizations") || path.startsWith("/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or malformed Authorization header");
            return;
        }

        String rawKey = header.substring("Bearer ".length());
        String hashedKey = apiKeyService.hash(rawKey);

        Optional<Organization> org = organizationRepository.findByApiKeyHash(hashedKey);

        if (org.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
            return;
        }

        request.setAttribute("organization", org.get());
        filterChain.doFilter(request, response);
    }
}