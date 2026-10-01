package com.emilio.streambox.exception;

import java.time.Duration;

/**
 * Excepción utilizada cuando se supera un límite de intentos (por ejemplo,
 * demasiados logins fallidos). Se traduce en una respuesta
 * {@code 429 Too Many Requests} con la cabecera {@code Retry-After}.
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    /**
     * @param message    mensaje descriptivo
     * @param retryAfter tiempo tras el cual se puede volver a intentar
     */
    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /**
     * @return tiempo que debe esperar el cliente antes de reintentar
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
