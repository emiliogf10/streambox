package com.emilio.streambox.security.body;

import java.io.IOException;

/**
 * El cuerpo de la petición ha superado el límite mientras se leía (petición
 * sin {@code Content-Length}, por trozos).
 *
 * <p>
 * Es una {@link IOException} porque la lanza el {@code InputStream} del
 * cuerpo, que solo puede lanzar esas. Quien lee (Jackson, al preparar un
 * {@code @RequestBody}) la envuelve en una
 * {@code HttpMessageNotReadableException}; {@code GlobalExceptionHandler} la
 * busca en la cadena de causas ({@link #isCauseOf(Throwable)}) y responde 413
 * {@code PAYLOAD_TOO_LARGE} en lugar del 400 {@code MALFORMED_REQUEST} de un
 * JSON mal formado.
 * </p>
 *
 * <p>
 * El mensaje es fijo y solo va al log: al cliente le llega siempre
 * {@link RequestBodySizeLimitFilter#MESSAGE}.
 * </p>
 */
public class RequestBodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Límite de cadenas de causas que se recorren (evita bucles en causas circulares). */
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * Crea la excepción.
     *
     * @param maxBytes límite que se ha superado, en bytes
     */
    public RequestBodyTooLargeException(long maxBytes) {
        super("El cuerpo de la petición supera el límite de " + maxBytes + " bytes");
    }

    /**
     * Indica si esta excepción está en la cadena de causas de {@code error}
     * (o es la propia {@code error}).
     *
     * @param error excepción que se está tratando (puede ser {@code null})
     * @return {@code true} si la causa de fondo es un cuerpo demasiado grande
     */
    public static boolean isCauseOf(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof RequestBodyTooLargeException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
