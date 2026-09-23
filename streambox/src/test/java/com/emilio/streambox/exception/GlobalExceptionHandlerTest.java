package com.emilio.streambox.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
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

        ResponseEntity<ErrorResponse> response = handler.handleValidationErrors(exception, request);

        assertError(response, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Los datos proporcionados no son válidos");
        assertEquals("Debe tener un formato válido", response.getBody().getValidationErrors().get("email"));
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
    void invalidSortOrDataAccessExceptionReturnsBadRequest() {
        IllegalArgumentException exception = new IllegalArgumentException("Invalid sort field");
                
        ResponseEntity<ErrorResponse> response = handler.handleInvalidUsageException(exception, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR, response.getBody().getCode());
        assertEquals("El campo de ordenacion especificado no es valido o la solicitud es incorrecta", response.getBody().getMessage());
    }
}
