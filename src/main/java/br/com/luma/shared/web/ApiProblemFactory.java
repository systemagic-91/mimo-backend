package br.com.luma.shared.web;

import java.net.URI;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemFactory {

    public ProblemDetail create(HttpStatus status, String code, String detail, String instance,
            Map<String, Object> fields) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://api.luma.com.br/problems/" + code.toLowerCase().replace('_', '-')));
        if (instance != null && instance.startsWith("/")) {
            problem.setInstance(URI.create(instance));
        }
        problem.setProperty("code", code);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        problem.setProperty("requestId", requestId == null ? "unknown" : requestId);
        if (fields != null && !fields.isEmpty()) {
            problem.setProperty("fields", fields);
        }
        return problem;
    }
}