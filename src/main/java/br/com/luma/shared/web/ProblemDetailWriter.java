package br.com.luma.shared.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ProblemDetailWriter {

    private final ObjectMapper objectMapper;
    private final ApiProblemFactory problemFactory;

    public ProblemDetailWriter(ObjectMapper objectMapper, ApiProblemFactory problemFactory) {
        this.objectMapper = objectMapper;
        this.problemFactory = problemFactory;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String code, String detail) throws IOException {
        ProblemDetail problem = problemFactory.create(status, code, detail, request.getRequestURI(), Map.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}