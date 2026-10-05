package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando se intenta crear un género, o renombrar uno
 * existente, con un nombre que ya tiene otro género (HTTP 409).
 *
 * <p>
 * Antes un duplicado solo lo detectaba la restricción {@code UNIQUE} de la
 * base de datos y la API respondía con el genérico
 * {@code DATA_INTEGRITY_VIOLATION}. Con esta excepción el cliente recibe un
 * código propio ({@code GENRE_ALREADY_EXISTS}) y un mensaje que nombra el
 * género, útil para mostrarlo tal cual en el formulario del panel de
 * administración.
 * </p>
 */
public class GenreAlreadyExistsException extends RuntimeException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje que indica qué nombre está repetido
     */
    public GenreAlreadyExistsException(String message) {
        super(message);
    }
}
