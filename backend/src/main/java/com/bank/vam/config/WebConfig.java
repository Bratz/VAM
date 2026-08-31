package com.bank.vam.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CORS configuration for the Aperture HTTP layer.
 *
 * <p>Allowed origins are externalised to the
 * {@code aperture.cors.allowed-origins} property (CSV). The default list
 * preserves the local dev setup so {@code npm run dev} on the Vite default
 * port 3000 (and the alternate port 5173) works without any extra
 * configuration.
 *
 * <p>For non-localhost deployments override via env var:
 * <pre>
 *   APERTURE_CORS_ALLOWED_ORIGINS=https://aperture.example.com,https://aperture-staging.example.com
 * </pre>
 * Spring Boot's relaxed binding maps the env var → the property automatically.
 */
@Configuration
public class WebConfig {

    /**
     * Comma-separated allowed origins. Empty entries (e.g. trailing commas)
     * are silently dropped.
     */
    @Value("${aperture.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173}")
    private String allowedOriginsCsv;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);

        List<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        config.setAllowedOrigins(origins);

        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setExposedHeaders(Arrays.asList("Authorization", "X-Request-Id"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
