package br.com.luma.auth.web;

import java.net.URI;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import br.com.luma.auth.exception.AuthException;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ProblemDetail> handleAuthException(AuthException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problem.setTitle(exception.getStatus().getReasonPhrase());
        problem.setType(URI.create("https://api.luma.com.br/problems/" + exception.getCode().toLowerCase().replace('_', '-')));
        problem.setProperty("code", exception.getCode());
        return ResponseEntity.status(exception.getStatus()).body(problem);
    }
}