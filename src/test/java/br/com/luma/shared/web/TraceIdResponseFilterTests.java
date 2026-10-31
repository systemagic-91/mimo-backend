package br.com.luma.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

class TraceIdResponseFilterTests {

    @Test
    void writesTheCurrentTraceIdInTheResponse() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdResponseFilter(provider(tracer)).doFilter(new MockHttpServletRequest(), response,
                new MockFilterChain());

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void skipsTheHeaderWhenThereIsNoActiveSpanOrTracer() throws Exception {
        Tracer tracer = mock(Tracer.class);
        MockHttpServletResponse withoutSpan = new MockHttpServletResponse();
        MockHttpServletResponse withoutTracer = new MockHttpServletResponse();

        new TraceIdResponseFilter(provider(tracer)).doFilter(new MockHttpServletRequest(), withoutSpan,
                new MockFilterChain());
        new TraceIdResponseFilter(provider(null)).doFilter(new MockHttpServletRequest(), withoutTracer,
                new MockFilterChain());

        assertThat(withoutSpan.getHeader("X-Trace-Id")).isNull();
        assertThat(withoutTracer.getHeader("X-Trace-Id")).isNull();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> provider(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return provider;
    }
}
