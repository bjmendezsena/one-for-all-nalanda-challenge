package com.nalanda.validation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private static final String CONFIGURED_API_KEY = "local-dev-api-key";

    private final ApiKeyFilter filter = new ApiKeyFilter(CONFIGURED_API_KEY, new ObjectMapper());

    @Test
    void should_rejectWithProblemDetails_when_theApiKeyHeaderIsMissing() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_rejectWithProblemDetails_when_theApiKeyIsWrong() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request("not-the-configured-key"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).contains("Invalid X-Api-Key header");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_rejectWithProblemDetails_when_theApiKeyIsBlank() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request("   "), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_continueTheChain_when_theApiKeyIsCorrect() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request(CONFIGURED_API_KEY), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat((HttpServletRequest) chain.getRequest()).isNotNull();
        assertThat((HttpServletResponse) chain.getResponse()).isNotNull();
    }

    private static MockHttpServletRequest request(String apiKey) {
        var request = new MockHttpServletRequest("POST", "/api/v1/validations");
        if (apiKey != null) {
            request.addHeader(ApiKeyFilter.API_KEY_HEADER, apiKey);
        }
        return request;
    }
}
