package br.com.luma.shared.web;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import br.com.luma.shared.exception.ApiException;
import br.com.luma.upload.service.PhotoApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiProblemFactory problemFactory;

    public ApiExceptionHandler(ApiProblemFactory problemFactory) {
        this.problemFactory = problemFactory;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(),
                exception.getFields(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, Object> fields = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(error -> error.getField(), TreeMap::new,
                        Collectors.mapping(error -> error.getDefaultMessage(), Collectors.toList())))
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A requisição contém campos inválidos.",
                fields, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, Object> fields = exception.getConstraintViolations().stream()
                .collect(Collectors.groupingBy(violation -> violation.getPropertyPath().toString(), TreeMap::new,
                        Collectors.mapping(violation -> violation.getMessage(), Collectors.toList())))
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A requisição contém parâmetros inválidos.",
                fields, request);
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ProblemDetail> handleBindingException(BindException exception, HttpServletRequest request) {
        Map<String, Object> fields = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(error -> error.getField(), TreeMap::new,
                        Collectors.mapping(error -> error.getDefaultMessage(), Collectors.toList())))
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A requisição contém campos inválidos.",
                fields, request);
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class, MultipartException.class })
    ResponseEntity<ProblemDetail> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "A requisição está malformada ou contém parâmetros inválidos.", Map.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleUploadTooLarge(MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return handleApiException(PhotoApplicationService.photoTooLarge(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "O Content-Type enviado não é aceito por este endpoint.", Map.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "O recurso solicitado não foi encontrado.", Map.of(), request);
    }

        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
                return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Você não tem permissão para acessar este recurso.",
                                Map.of(), request);
        }

    @ExceptionHandler({ DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class })
    ResponseEntity<ProblemDetail> handleConflict(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONFLICT", "A operação conflita com o estado atual dos dados.",
                Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled request failure requestId={} exceptionType={}",
                MDC.get(RequestIdFilter.MDC_KEY), exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Ocorreu um erro interno. Tente novamente mais tarde.", Map.of(), request);
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status, String code, String detail,
            Map<String, Object> fields, HttpServletRequest request) {
        ProblemDetail problem = problemFactory.create(status, code, detail, request.getRequestURI(), fields);
        return ResponseEntity.status(status).body(problem);
    }
}