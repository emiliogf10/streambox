package com.emilio.streambox.exception;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/** Manejador global de excepciones de la API REST de Streambox. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_MESSAGE = "Los datos proporcionados no son válidos";
    private static final String INTEGRITY_MESSAGE =
            "No se puede completar la operación porque entra en conflicto con datos existentes";
    private static final String INTERNAL_ERROR_MESSAGE =
            "Se ha producido un error interno. Inténtalo de nuevo más tarde";


    @ExceptionHandler(MovieNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMovieNotFound(
            MovieNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                exception.getMessage(), request);
    }

    @ExceptionHandler(GenreNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGenreNotFound(
            GenreNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                exception.getMessage(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(
            UserNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                exception.getMessage(), request);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExists(
            UserAlreadyExistsException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS,
                exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE, request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, String> errors = exception.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> {
                            String p = v.getPropertyPath().toString();
                            return p.contains(".") ? p.substring(p.lastIndexOf('.') + 1) : p;
                        },
                        v -> v.getMessage(),
                        (a, b) -> a));
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE, request, errors);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.DATA_INTEGRITY_VIOLATION,
                INTEGRITY_MESSAGE, request);
    }

    @ExceptionHandler(AmbiguousTitleException.class)
    public ResponseEntity<ErrorResponse> handleAmbiguousTitle(
            AmbiguousTitleException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.AMBIGUOUS_TITLE,
                exception.getMessage(), request);
    }

    @ExceptionHandler({InvalidDataAccessApiUsageException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleInvalidUsageException(
            Exception exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "El campo de ordenacion especificado no es valido o la solicitud es incorrecta", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        LOGGER.error("Error no controlado al procesar {}", request.getRequestURI(), exception);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                INTERNAL_ERROR_MESSAGE, request);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status, ErrorCode code, String message, HttpServletRequest request) {
        return buildErrorResponse(status, code, message, request, null);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status, ErrorCode code, String message,
            HttpServletRequest request, Map<String, String> validationErrors) {
        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(), status.value(), status.getReasonPhrase(),
                code, message, request.getRequestURI(), validationErrors);
        return ResponseEntity.status(status).body(error);
    }
}

