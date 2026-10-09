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
    INTERNAL_ERROR,

    /** Existe más de una película con el mismo título; usar el ID para la operación. */
    AMBIGUOUS_TITLE,

    /** El usuario está autenticado pero no tiene permisos para acceder al recurso. */
    ACCESS_DENIED,

    /** El cuerpo de la petición no se puede interpretar (JSON mal formado, tipos incorrectos). */
    MALFORMED_REQUEST,

    /** El método HTTP no está soportado por el endpoint. */
    METHOD_NOT_ALLOWED,

    /** El tipo de contenido de la petición no está soportado. */
    UNSUPPORTED_MEDIA_TYPE,

    /**
     * El cliente pidió en la cabecera {@code Accept} un formato que la API no
     * produce (o la cabecera está mal formada): la API solo responde en JSON
     * (406). El propio error se envía en JSON, porque de otro modo el cliente
     * no podría leer el motivo.
     */
    NOT_ACCEPTABLE,

    /**
     * Se ha superado el límite de peticiones permitido desde una misma IP
     * (login, registro o renovación de sesión con {@code POST /api/auth/refresh}).
     * Va con la cabecera {@code Retry-After}.
     */
    RATE_LIMIT_EXCEEDED,

    /**
     * La cuenta (el email) está bloqueada temporalmente por demasiados logins
     * fallidos. Lo devuelve el fallo que agota los intentos y cualquier intento
     * posterior mientras dure el bloqueo, aunque la contraseña sea correcta. Va
     * con la cabecera {@code Retry-After} y no depende de que el email exista.
     */
    ACCOUNT_LOCKED,

    /**
     * Ya existe otro género con ese nombre (una vez normalizado, sin distinguir
     * mayúsculas). Lo devuelven el alta y la edición de géneros.
     */
    GENRE_ALREADY_EXISTS,

    /**
     * El género no se puede borrar porque alguna película o serie lo tiene
     * asignado; hay que quitárselo antes a esas películas y series.
     */
    GENRE_IN_USE,

    /**
     * Ya existe en la serie un episodio con la misma temporada y número. Lo
     * devuelven el alta y la edición de episodios (409).
     */
    EPISODE_ALREADY_EXISTS,

    /** La serie ya pertenece a la lista de favoritos del usuario (409). */
    SERIES_ALREADY_IN_FAVORITES,

    /**
     * La serie existe, pero no pertenece a la lista de favoritos del usuario
     * (404). Si la serie no existe, el código es {@link #RESOURCE_NOT_FOUND}.
     */
    SERIES_NOT_IN_FAVORITES,

    /**
     * Petición no segura (POST/PUT/PATCH/DELETE) autenticada por cookie a la que
     * falta la cabecera {@code X-Requested-With: StreamBox} (defensa CSRF, 403).
     * El refresh y el logout la exigen siempre, también con Bearer: trabajan
     * con la cookie {@code streambox_refresh} y su respuesta cambia las cookies.
     */
    CSRF_REJECTED,

    /**
     * {@code POST /api/auth/refresh} no puede renovar la sesión (401): el
     * refresh token falta, no existe, ha caducado (él o su familia de 30 días),
     * está revocado (logout) o se ha reutilizado (posible robo: se revoca la
     * familia). Es distinto de {@link #INVALID_CREDENTIALS} porque aquí no hay
     * credenciales que corregir: el cliente debe volver a la pantalla de login.
     */
    SESSION_EXPIRED
}
