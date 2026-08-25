package com.nalanda.validation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void should_reuseTheClientCorrelationId_when_theRequestCarriesOne() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/validations");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "client-supplied-id");
        var observed = new AtomicReference<String>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(observed));

        assertThat(observed.get()).isEqualTo("client-supplied-id");
    }

    @Test
    void should_generateACorrelationId_when_theRequestCarriesNone() throws Exception {
        var observed = new AtomicReference<String>();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/validations"),
                new MockHttpServletResponse(),
                capturingChain(observed));

        assertThat(observed.get()).isNotBlank();
        assertThat(UUID.fromString(observed.get())).isNotNull();
    }

    @Test
    void should_clearTheMdc_when_theChainCompletes() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/validations"),
                new MockHttpServletResponse(),
                capturingChain(new AtomicReference<>()));

        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    private static FilterChain capturingChain(AtomicReference<String> observed) {
        return (request, response) -> observed.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));
    }
}
