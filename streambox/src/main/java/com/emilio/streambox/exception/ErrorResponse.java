package com.emilio.streambox.exception;

import java.time.LocalDateTime;

/**
 * Representa la estructura estándar de las respuestas de error
 * devueltas por la API REST de Streambox.
 *
 * <p>
 * Permite proporcionar información común sobre el error, como
 * el código HTTP, el mensaje descriptivo y el momento en el que
 * se produjo.
 * </p>
 */
public class ErrorResponse {

    private int status;
    private String message;
    private LocalDateTime timestamp;

    /**
     * Crea una respuesta de error.
     *
     * @param status    código de estado HTTP asociado al error
     * @param message   mensaje descriptivo del error
     * @param timestamp fecha y hora en la que se produjo el error
     */
    public ErrorResponse(
            int status,
            String message,
            LocalDateTime timestamp) {

        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
    }

    /**
     * @return código de estado HTTP
     */
    public int getStatus() {
        return status;
    }

    /**
     * @return mensaje descriptivo del error
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return fecha y hora en la que se produjo el error
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}