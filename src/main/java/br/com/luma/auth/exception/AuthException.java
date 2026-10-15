package br.com.luma.auth.exception;

import org.springframework.http.HttpStatus;

import br.com.luma.shared.exception.ApiException;

public class AuthException extends ApiException {

    public AuthException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}