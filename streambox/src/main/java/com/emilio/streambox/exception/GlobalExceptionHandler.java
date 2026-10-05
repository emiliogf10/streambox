package com.emilio.streambox.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Manejador global de excepciones de la API REST de Streambox.
 *
 * <p>
 * Extiende {@link ResponseEntityExceptionHandler} para que las excepciones
 * estándar de Spring MVC (JSON mal formado, parámetro ausente, método no
 * soportado, ruta inexistente...) se traduzcan en su código 4xx correcto y
 * con el mismo formato {@link ErrorResponse} que el resto de la API. Sin
 * esto, un {@code @ExceptionHandler(Exception.class)} las capturaría y las
 * convertiría en errores 500.
 * </p>
 *
 * <p>
 * Los mensajes de las excepciones del framework no se devuelven al cliente
 * porque pueden revelar detalles internos (nombres de clases, rutas,
 * estructura del JSON esperado).
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_MESSAGE = "Los datos proporcionados no son válidos";
    private static final String MALFORMED_MESSAGE =
            "La petición no se puede interpretar. Revisa el formato de los datos";
    private static final String INTEGRITY_MESSAGE =
            "No se puede completar la operación porque entra en conflicto con datos existentes";
    private static final String INTERNAL_ERROR_MESSAGE =
            "Se ha producido un error interno. Inténtalo de nuevo más tarde";

    // ------------------------------------------------------------------
    // Excepciones de dominio
    // ------------------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                exception.getMessage(), request);
    }

    @ExceptionHandler(MovieNotInFavoritesException.class)
    public ResponseEntity<ErrorResponse> handleMovieNotInFavorites(
            MovieNotInFavoritesException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.MOVIE_NOT_IN_FAVORITES,
                exception.getMessage(), request);
    }

    @ExceptionHandler(MovieAlreadyInFavoritesException.class)
    public ResponseEntity<ErrorResponse> handleMovieAlreadyInFavorites(
            MovieAlreadyInFavoritesException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.MOVIE_ALREADY_IN_FAVORITES,
                exception.getMessage(), request);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExists(
            UserAlreadyExistsException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS,
                exception.getMessage(), request);
    }

    @ExceptionHandler(GenreAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleGenreAlreadyExists(
            GenreAlreadyExistsException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.GENRE_ALREADY_EXISTS,
                exception.getMessage(), request);
    }

    @ExceptionHandler(GenreInUseException.class)
    public ResponseEntity<ErrorResponse> handleGenreInUse(
            GenreInUseException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.GENRE_IN_USE,
                exception.getMessage(), request);
    }

    /**
     * 401 del login. Incluye {@code remainingAttempts} (intentos antes del
     * bloqueo) cuando la excepción lo trae; si no, el campo se omite.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        ErrorResponse error = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(),
                ErrorCode.INVALID_CREDENTIALS, exception.getMessage(), request.getRequestURI(),
                null, exception.getRemainingAttempts());
        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(AmbiguousTitleException.class)
    public ResponseEntity<ErrorResponse> handleAmbiguousTitle(
            AmbiguousTitleException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.AMBIGUOUS_TITLE,
                exception.getMessage(), request);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(
            TooManyRequestsException exception, HttpServletRequest request) {
        return tooManyRequests(ErrorCode.RATE_LIMIT_EXCEEDED, exception, request);
    }

    /**
     * Cuenta bloqueada por logins fallidos: 429 con su propio código. Spring
     * elige este método y no el de {@link TooManyRequestsException} porque es
     * el manejador de la clase más concreta.
     */
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLocked(
            AccountLockedException exception, HttpServletRequest request) {
        return tooManyRequests(ErrorCode.ACCOUNT_LOCKED, exception, request);
    }

    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameter(
            InvalidParameterException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE, request,
                Map.of(exception.getParameter(), exception.getMessage()));
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

    /**
     * Último recurso: errores no previstos. Se registran con su traza
     * completa y se devuelve un mensaje genérico al cliente.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        LOGGER.error("Error no controlado al procesar {}", request.getRequestURI(), exception);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                INTERNAL_ERROR_MESSAGE, request);
    }

    // ------------------------------------------------------------------
    // Excepciones estándar de Spring MVC (heredadas de
    // ResponseEntityExceptionHandler)
    // ------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE, request, headers, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result ->
                result.getResolvableErrors().forEach(error ->
                        errors.putIfAbsent(
                                String.valueOf(result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage())));
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE, request, headers, errors);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE, request, headers,
                Map.of(exception.getParameterName(), "El parámetro es obligatorio"));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String property = exception.getPropertyName() != null
                ? exception.getPropertyName()
                : "parameter";
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE, request, headers,
                Map.of(property, "El valor no tiene el formato esperado"));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                MALFORMED_MESSAGE, request, headers, null);
    }

    /**
     * Punto común por el que pasan el resto de excepciones estándar de Spring
     * MVC (404 de ruta inexistente, 405, 415, 406...). Se sustituye el
     * {@code ProblemDetail} por defecto por el {@link ErrorResponse} de la API.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object responseBody, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        HttpStatus status = HttpStatus.valueOf(statusCode.value());

        if (status.is5xxServerError()) {
            LOGGER.error("Error al procesar {}", path(request), exception);
            return body(status, ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE,
                    request, headers, null);
        }

        ErrorCode code;
        String message;
        switch (status) {
            case NOT_FOUND -> {
                code = ErrorCode.RESOURCE_NOT_FOUND;
                message = "El recurso solicitado no existe";
            }
            case METHOD_NOT_ALLOWED -> {
                code = ErrorCode.METHOD_NOT_ALLOWED;
                message = "El método HTTP no está permitido para este recurso";
            }
            case UNSUPPORTED_MEDIA_TYPE -> {
                code = ErrorCode.UNSUPPORTED_MEDIA_TYPE;
                message = "El tipo de contenido no está soportado";
            }
            default -> {
                code = ErrorCode.MALFORMED_REQUEST;
                message = MALFORMED_MESSAGE;
            }
        }
        return body(status, code, message, request, headers, null);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status, ErrorCode code, String message, HttpServletRequest request) {
        return buildErrorResponse(status, code, message, request, null);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status, ErrorCode code, String message,
            HttpServletRequest request, Map<String, String> validationErrors) {
        ErrorResponse error = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(),
                code, message, request.getRequestURI(), validationErrors);
        return ResponseEntity.status(status).body(error);
    }

    /** 429 con la cabecera {@code Retry-After} en segundos. */
    private ResponseEntity<ErrorResponse> tooManyRequests(
            ErrorCode code, TooManyRequestsException exception, HttpServletRequest request) {
        ResponseEntity<ErrorResponse> response = buildErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS, code, exception.getMessage(), request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER,
                        String.valueOf(exception.getRetryAfter().toSeconds()))
                .body(response.getBody());
    }

    /** Variante para los métodos heredados, que trabajan con {@link WebRequest}. */
    private ResponseEntity<Object> body(
            HttpStatus status, ErrorCode code, String message, WebRequest request,
            HttpHeaders headers, Map<String, String> validationErrors) {
        ErrorResponse error = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(),
                code, message, path(request), validationErrors);
        return ResponseEntity.status(status).headers(headers).body(error);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }
}
