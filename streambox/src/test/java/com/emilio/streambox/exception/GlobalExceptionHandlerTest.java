package com.emilio.streambox.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;

/**
 * Pruebas del contrato común de errores. Se prueban directamente los handlers
 * para verificar el estado HTTP, el código funcional y que no se filtren
 * detalles de infraestructura.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/users");
    }

    @Test
    void userAlreadyExistsReturnsConflictWithStableCode() {
        ResponseEntity<ErrorResponse> response = handler.handleUserAlreadyExists(
                new UserAlreadyExistsException("El correo electrónico ya está en uso"), request);

        assertError(response, HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS,
                "El correo electrónico ya está en uso");
    }

    @Test
    void dataIntegrityViolationDoesNotExposeDatabaseDetails() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("duplicate key value violates unique constraint users_email_key"),
                request);

        assertError(response, HttpStatus.CONFLICT, ErrorCode.DATA_INTEGRITY_VIOLATION,
                "No se puede completar la operación porque entra en conflicto con datos existentes");
    }

    @Test
    void validationErrorIncludesFieldErrorsAndStableCode() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "Debe tener un formato válido"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

        ErrorResponse body = (ErrorResponse) response.getBody();
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, body.getCode());
        assertEquals("Los datos proporcionados no son válidos", body.getMessage());
        assertEquals("/api/users", body.getPath());
        assertEquals("Debe tener un formato válido", body.getValidationErrors().get("email"));
    }

    @Test
    void unexpectedExceptionReturnsSafeInternalError() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new IllegalStateException("internal implementation detail"), request);

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Se ha producido un error interno. Inténtalo de nuevo más tarde");
        assertNull(response.getBody().getValidationErrors());
    }

    private void assertError(ResponseEntity<ErrorResponse> response, HttpStatus status,
            ErrorCode code, String message) {

        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(status.value(), response.getBody().getStatus());
        assertEquals(status.getReasonPhrase(), response.getBody().getError());
        assertEquals(code, response.getBody().getCode());
        assertEquals(message, response.getBody().getMessage());
        assertEquals("/api/users", response.getBody().getPath());
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    void movieAlreadyInFavoritesReturnsConflictWithSpecificCode() {
        ResponseEntity<ErrorResponse> response = handler.handleMovieAlreadyInFavorites(
                new MovieAlreadyInFavoritesException("Ya está en favoritos"), request);

        assertError(response, HttpStatus.CONFLICT, ErrorCode.MOVIE_ALREADY_IN_FAVORITES,
                "Ya está en favoritos");
    }

    @Test
    void movieNotInFavoritesReturnsNotFoundWithSpecificCode() {
        ResponseEntity<ErrorResponse> response = handler.handleMovieNotInFavorites(
                new MovieNotInFavoritesException("No está en favoritos"), request);

        assertError(response, HttpStatus.NOT_FOUND, ErrorCode.MOVIE_NOT_IN_FAVORITES,
                "No está en favoritos");
    }

    @Test
    void anyResourceNotFoundSubclassReturnsNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleResourceNotFound(
                new GenreNotFoundException("Género no encontrado: 9"), request);

        assertError(response, HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                "Género no encontrado: 9");
    }

    @Test
    void invalidParameterReportsTheOffendingParameter() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidParameter(
                new InvalidParameterException("sort", "Campo no permitido"), request);

        assertError(response, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Los datos proporcionados no son válidos");
        assertEquals("Campo no permitido", response.getBody().getValidationErrors().get("sort"));
    }

    @Test
    void invalidCredentialsIncludesRemainingAttempts() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidCredentials(
                new InvalidCredentialsException("Email o contraseña incorrectos", 3), request);

        assertError(response, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                "Email o contraseña incorrectos");
        assertEquals(3, response.getBody().getRemainingAttempts());
    }

    @Test
    void invalidCredentialsWithoutAttemptsOmitsTheField() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidCredentials(
                new InvalidCredentialsException("Email o contraseña incorrectos"), request);

        assertNull(response.getBody().getRemainingAttempts());
    }

    @Test
    void accountLockedReturns429WithItsOwnCodeAndRetryAfter() {
        ResponseEntity<ErrorResponse> response = handler.handleAccountLocked(
                new AccountLockedException("Cuenta bloqueada", Duration.ofSeconds(840)), request);

        assertError(response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.ACCOUNT_LOCKED, "Cuenta bloqueada");
        assertEquals("840", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNull(response.getBody().getRemainingAttempts());
    }

    @Test
    void genericTooManyRequestsKeepsRateLimitExceeded() {
        ResponseEntity<ErrorResponse> response = handler.handleTooManyRequests(
                new TooManyRequestsException("Demasiadas peticiones", Duration.ofSeconds(30)), request);

        assertError(response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMIT_EXCEEDED,
                "Demasiadas peticiones");
        assertEquals("30", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void otherErrorsDoNotCarryRemainingAttempts() {
        ResponseEntity<ErrorResponse> response = handler.handleResourceNotFound(
                new GenreNotFoundException("Género no encontrado: 9"), request);

        assertNull(response.getBody().getRemainingAttempts());
    }

    @Test
    void illegalArgumentExceptionIsNoLongerHiddenAsBadRequest() {
        // Un IllegalArgumentException inesperado es un bug nuestro: debe ser
        // un 500 registrado en logs, no un 400 que lo oculte.
        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new IllegalArgumentException("bug"), request);

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Se ha producido un error interno. Inténtalo de nuevo más tarde");
    }
}
