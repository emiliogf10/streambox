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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.web.firewall.RequestRejectedException;
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
import com.emilio.streambox.security.JsonRequestRejectedHandler;

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
 *
 * <p>
 * <b>Los errores salen siempre en JSON, pida lo que pida el cliente.</b> Todas
 * las respuestas de este manejador fijan {@code Content-Type: application/json}
 * (ver {@link #jsonError(HttpStatusCode)}). Spring MVC
 * ({@code AbstractMessageConverterMethodProcessor#writeWithMessageConverters})
 * solo negocia el formato con la cabecera {@code Accept} cuando la respuesta no
 * trae ya un tipo concreto; si lo trae, lo usa tal cual y escribe con el
 * conversor JSON. Sin esto, un {@code Accept: application/yaml} (o XML, o HTML)
 * hacía que el propio manejador fallara al escribir el {@code ErrorResponse};
 * Spring acababa en {@code sendError}, Tomcat reenviaba a {@code /error} y la
 * seguridad respondía un 401 falso en lugar del 404, 400, 406 o 415 real (ver
 * {@code ErrorResponseAlwaysJsonTomcatIntegrationTest}). Responder el error en
 * JSON aunque el cliente pidiera otra cosa lo permite el estándar (RFC 9110,
 * apartado 12.5.1: si ninguna representación encaja con {@code Accept}, el
 * servidor puede responder 406 o ignorar la cabecera) y es lo útil para el
 * cliente: recibe el código real y el {@code code} estable de la API.
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_MESSAGE = "Los datos proporcionados no son válidos";
    private static final String MALFORMED_MESSAGE = GenericHttpError.MALFORMED_MESSAGE;
    private static final String INTEGRITY_MESSAGE =
            "No se puede completar la operación porque entra en conflicto con datos existentes";
    private static final String INTERNAL_ERROR_MESSAGE = GenericHttpError.INTERNAL_ERROR_MESSAGE;

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

    /**
     * 404 específico de "Mi lista" de series: la serie existe pero no estaba
     * en la lista. Spring lo elige antes que el de
     * {@link ResourceNotFoundException} por ser la clase más concreta.
     */
    @ExceptionHandler(SeriesNotInFavoritesException.class)
    public ResponseEntity<ErrorResponse> handleSeriesNotInFavorites(
            SeriesNotInFavoritesException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.SERIES_NOT_IN_FAVORITES,
                exception.getMessage(), request);
    }

    /** 409 de "Mi lista" de series: la serie ya estaba (también si dos altas se cruzan). */
    @ExceptionHandler(SeriesAlreadyInFavoritesException.class)
    public ResponseEntity<ErrorResponse> handleSeriesAlreadyInFavorites(
            SeriesAlreadyInFavoritesException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.SERIES_ALREADY_IN_FAVORITES,
                exception.getMessage(), request);
    }

    /** 409 al crear o mover un episodio a una temporada y número ya ocupados en la serie. */
    @ExceptionHandler(EpisodeAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEpisodeAlreadyExists(
            EpisodeAlreadyExistsException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.EPISODE_ALREADY_EXISTS,
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
        return jsonError(status).body(error);
    }

    /**
     * Refresh token que no sirve para renovar la sesión: 401 {@code SESSION_EXPIRED},
     * mismo cuerpo en todos los casos. Las cookies borradas las añade
     * {@code AuthController} antes de relanzar la excepción.
     */
    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<ErrorResponse> handleSessionExpired(
            SessionExpiredException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.SESSION_EXPIRED,
                exception.getMessage(), request);
    }

    /**
     * Cambio de contraseña con la contraseña actual incorrecta: 400
     * {@code CURRENT_PASSWORD_INCORRECT} con el detalle en el campo
     * {@code currentPassword}, para que el formulario lo muestre junto a él.
     */
    @ExceptionHandler(CurrentPasswordIncorrectException.class)
    public ResponseEntity<ErrorResponse> handleCurrentPasswordIncorrect(
            CurrentPasswordIncorrectException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.CURRENT_PASSWORD_INCORRECT,
                exception.getMessage(), request,
                Map.of(CurrentPasswordIncorrectException.FIELD, exception.getMessage()));
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
     * Petición rechazada por el cortafuegos HTTP de Spring Security
     * ({@code StrictHttpFirewall}) <b>dentro</b> de Spring MVC: 400
     * {@code MALFORMED_REQUEST}, igual que cuando la rechaza antes de la
     * cadena de filtros.
     *
     * <p>
     * <b>Por qué llega aquí.</b> El cortafuegos comprueba las cabeceras de
     * forma perezosa, cuando alguien las lee. Si la primera lectura de una
     * cabecera no válida ocurre dentro de Spring MVC (por ejemplo, el
     * {@code Content-Type} al preparar el {@code @RequestBody}), la excepción
     * ya no pasa por {@code FilterChainProxy} ni por su
     * {@code JsonRequestRejectedHandler}: sin este método la atrapaba
     * {@link #handleUnexpectedException} y respondía 500 con una línea
     * {@code ERROR} y su traza. En una ruta pública como el login, cualquiera
     * podía llenar el log de errores falsos.
     * </p>
     *
     * <p>
     * Se responde con el mismo mensaje que {@code JsonRequestRejectedHandler},
     * para que el cliente no note en qué momento se rechazó, y el de la
     * excepción (qué regla saltó y con qué cadena) solo va al log en
     * {@code DEBUG}, como allí.
     * </p>
     */
    @ExceptionHandler(RequestRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRequestRejected(
            RequestRejectedException exception, HttpServletRequest request) {
        LOGGER.debug("Petición rechazada por el cortafuegos HTTP dentro de Spring MVC: {}",
                exception.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                JsonRequestRejectedHandler.MESSAGE, request);
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
     * El código y el mensaje salen de {@link GenericHttpError}, la misma tabla
     * que usa {@link ApiErrorController} para los errores que llegan por el
     * despacho de error de Tomcat: un mismo estado da el mismo {@code code}
     * venga por donde venga.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object responseBody, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        if (status.is5xxServerError()) {
            LOGGER.error("Error al procesar {}", path(request), exception);
        }
        GenericHttpError error = GenericHttpError.forStatus(status);
        return body(status, error.code(), error.message(), request, headers, null);
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
        return jsonError(status).body(error);
    }

    /** 429 con la cabecera {@code Retry-After} en segundos. */
    private ResponseEntity<ErrorResponse> tooManyRequests(
            ErrorCode code, TooManyRequestsException exception, HttpServletRequest request) {
        ResponseEntity<ErrorResponse> response = buildErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS, code, exception.getMessage(), request);
        return jsonError(HttpStatus.TOO_MANY_REQUESTS)
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
        // contentType después de headers: si Spring hubiera puesto otro tipo
        // en las cabeceras de la excepción, gana el JSON.
        return jsonError(status).headers(headers).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    /**
     * Inicio de toda respuesta de error: el estado y {@code Content-Type:
     * application/json} ya fijado, para que Spring no negocie el formato con el
     * {@code Accept} del cliente (ver el Javadoc de la clase).
     *
     * @param status código HTTP del error
     * @return constructor de la respuesta con el tipo JSON fijado
     */
    private static ResponseEntity.BodyBuilder jsonError(HttpStatusCode status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }
}
