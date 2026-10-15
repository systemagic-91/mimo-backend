package br.com.luma.shared.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> fields;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = Map.copyOf(fields);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getFields() {
        return fields;
    }
}