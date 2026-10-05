package com.emilio.streambox.exception;

import java.time.Duration;

/**
 * La cuenta está bloqueada temporalmente por demasiados logins fallidos.
 *
 * <p>
 * Es un caso particular de {@link TooManyRequestsException} (también es un
 * 429 con {@code Retry-After}), pero con su propio código
 * ({@code ACCOUNT_LOCKED}) para que el cliente distinga el bloqueo de la
 * cuenta del límite de peticiones por IP ({@code RATE_LIMIT_EXCEEDED}): el
 * primero se resuelve esperando aunque se cambie de red; el segundo, no
 * insistiendo desde la misma IP.
 * </p>
 */
public class AccountLockedException extends TooManyRequestsException {

    /**
     * @param message    mensaje para el usuario, con la duración del bloqueo
     * @param retryAfter tiempo que falta para poder volver a intentarlo
     */
    public AccountLockedException(String message, Duration retryAfter) {
        super(message, retryAfter);
    }
}
