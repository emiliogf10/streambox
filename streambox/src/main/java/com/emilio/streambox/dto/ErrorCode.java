package com.emilio.streambox.dto;

/**
 * Códigos funcionales estables que identifican los errores devueltos por la API.
 *
 * <p>Los clientes deben basar su lógica en este código y no en el texto de
 * {@code message}, que está destinado a mostrarse al usuario y puede cambiar.</p>
 */
public enum ErrorCode {

    /** El recurso solicitado no existe. */
    RESOURCE_NOT_FOUND,

    /** El nombre de usuario o el correo electrónico ya están registrados. */
    USER_ALREADY_EXISTS,

    /** La película ya pertenece a la lista de favoritos del usuario. */
    MOVIE_ALREADY_IN_FAVORITES,

    /** La película no pertenece a la lista de favoritos del usuario. */
    MOVIE_NOT_IN_FAVORITES,

    /** Los datos de entrada no cumplen las reglas de validación. */
    VALIDATION_ERROR,

    /** Las credenciales de autenticación no son válidas. */
    INVALID_CREDENTIALS,

    /** La operación entra en conflicto con una restricción de datos. */
    DATA_INTEGRITY_VIOLATION,

    /** Se produjo un error no controlado en el servidor. */
    INTERNAL_ERROR
}
