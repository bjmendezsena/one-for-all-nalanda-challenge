package com.nalanda.validation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nalanda.validation.adapter.in.web.ApiKeyFilter;
import com.nalanda.validation.adapter.in.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the two web-boundary filters over the documented API surface only. The correlation id
 * is established first so a rejected request is still logged under its own id.
 */
@Configuration
public class WebFilterConfig {

    private static final String API_URL_PATTERN = "/api/v1/*";

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        var registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns(API_URL_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(
            @Value("${security.api-key}") String configuredApiKey, ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(new ApiKeyFilter(configuredApiKey, objectMapper));
        registration.addUrlPatterns(API_URL_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
