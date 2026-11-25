package com.nexusfin.notification.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdMdcFilterTest {

    @Test
    @DisplayName("Phase 6.4: Propagates X-Correlation-Id to MDC and response header, and cleans up after request")
    void propagatesAndClearsCorrelationIdInMdc() throws Exception {
        CorrelationIdMdcFilter filter = new CorrelationIdMdcFilter(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader("X-Correlation-Id", "CORR-NOTIFY-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> capturedInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> capturedInChain.set(MDC.get("X-Correlation-Id"));

        filter.doFilter(request, response, chain);

        assertThat(capturedInChain.get()).isEqualTo("CORR-NOTIFY-123");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("CORR-NOTIFY-123");
        assertThat(MDC.get("X-Correlation-Id")).isNull();
    }
}
