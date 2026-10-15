package br.com.luma.auth.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public class AuthException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AuthException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}