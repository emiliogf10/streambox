package com.emilio.streambox.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import com.emilio.streambox.dto.ErrorCode;

/**
 * Tabla estado HTTP → {@code code} que comparten {@link GlobalExceptionHandler}
 * y {@link ApiErrorController}. Fija el contrato que ve el cliente: si alguien
 * cambia un código aquí, cambia en los dos caminos a la vez, y este test lo
 * hace visible.
 */
class GenericHttpErrorTest {

    @ParameterizedTest
    @CsvSource({
            "BAD_REQUEST, MALFORMED_REQUEST",
            "UNAUTHORIZED, INVALID_CREDENTIALS",
            "FORBIDDEN, ACCESS_DENIED",
            "NOT_FOUND, RESOURCE_NOT_FOUND",
            "METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED",
            "NOT_ACCEPTABLE, NOT_ACCEPTABLE",
            "PAYLOAD_TOO_LARGE, MALFORMED_REQUEST",
            "UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE",
            "INTERNAL_SERVER_ERROR, INTERNAL_ERROR",
            "SERVICE_UNAVAILABLE, INTERNAL_ERROR" })
    void cadaEstadoTieneSuCode(HttpStatus status, ErrorCode code) {
        assertEquals(code, GenericHttpError.forStatus(status).code());
    }

    /** Todos los 5xx comparten mensaje genérico: nunca se describe el fallo interno. */
    @ParameterizedTest
    @MethodSource("errorStatuses")
    void todoEstadoDeErrorTieneMensajeYLos5xxElGenerico(HttpStatus status) {
        GenericHttpError error = GenericHttpError.forStatus(status);
        assertFalse(error.message().isBlank(), status.toString());
        if (status.is5xxServerError()) {
            assertEquals(ErrorCode.INTERNAL_ERROR, error.code(), status.toString());
            assertEquals(GenericHttpError.INTERNAL_ERROR_MESSAGE, error.message(), status.toString());
        }
    }

    /** Todos los estados 4xx y 5xx que conoce Spring. */
    static Stream<HttpStatus> errorStatuses() {
        return Arrays.stream(HttpStatus.values()).filter(HttpStatus::isError);
    }
}
