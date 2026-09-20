package com.emilio.streambox.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Estructura única y pública de todas las respuestas de error de la API.
 *
 * <p>El campo {@link #code} es el contrato estable para los clientes. El
 * campo {@link #message} está pensado para mostrar información legible y no
 * debe usarse para tomar decisiones de negocio.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final LocalDateTime timestamp;
    private final int status;
    private final String error;
    private final ErrorCode code;
    private final String message;
    private final String path;
    private final Map<String, String> validationErrors;

    /**
     * Crea una respuesta de error sin detalle de validaciones por campo.
     *
     * @param timestamp fecha y hora en la que se produjo el error
     * @param status código HTTP de la respuesta
     * @param error descripción estándar del estado HTTP
     * @param code código funcional estable del error
     * @param message mensaje descriptivo orientado al cliente
     * @param path ruta de la petición que produjo el error
     */
    public ErrorResponse(
            LocalDateTime timestamp,
            int status,
            String error,
            ErrorCode code,
            String message,
            String path) {

        this(timestamp, status, error, code, message, path, null);
    }

    /**
     * Crea una respuesta de error con detalle de validaciones por campo.
     *
     * @param validationErrors errores de validación agrupados por campo
     */
    public ErrorResponse(
            LocalDateTime timestamp,
            int status,
            String error,
            ErrorCode code,
            String message,
            String path,
            Map<String, String> validationErrors) {

        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.code = code;
        this.message = message;
        this.path = path;
        this.validationErrors = validationErrors;
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public int getStatus() { return status; }
    public String getError() { return error; }
    public ErrorCode getCode() { return code; }
    public String getMessage() { return message; }
    public String getPath() { return path; }
    public Map<String, String> getValidationErrors() { return validationErrors; }
}
