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

    /**
     * Las credenciales de autenticación no son válidas. En el login incluye,
     * con la misma respuesta, el email inexistente, la contraseña incorrecta y
     * la cuenta pendiente de confirmar el correo aunque la contraseña sea
     * correcta (para no revelar qué emails tienen cuenta).
     */
    INVALID_CREDENTIALS,

    /*
     * Aquí estaba DATA_INTEGRITY_VIOLATION (409, "la operación entra en
     * conflicto con una restricción de datos"). Se retiró: las violaciones
     * esperadas ya tienen su código propio en cada servicio, y las que ningún
     * servicio traduce son un fallo del servidor (INTERNAL_ERROR, ver
     * GlobalExceptionHandler#handleDataIntegrityViolation).
     */

    /**
     * Se produjo un error no controlado en el servidor. Incluye las violaciones
     * de integridad de la base de datos que ningún servicio esperaba.
     */
    INTERNAL_ERROR,

    /** Existe más de una película con el mismo título; usar el ID para la operación. */
    AMBIGUOUS_TITLE,

    /** El usuario está autenticado pero no tiene permisos para acceder al recurso. */
    ACCESS_DENIED,

    /**
     * El cuerpo de la petición no se puede interpretar (JSON mal formado, tipos
     * incorrectos). Incluye un valor de otro tipo JSON que el del campo: un
     * número o booleano en un texto, un texto en un número, un decimal en un
     * entero (no se convierten; ver {@code StrictJsonScalarsConfig}).
     */
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
     * (login, registro o renovación de sesión con {@code POST /api/auth/refresh})
     * o, por cuenta, el de intentos con la contraseña actual incorrecta al
     * cambiarla ({@code PUT /api/users/me/password}). Va con la cabecera
     * {@code Retry-After}.
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
    SESSION_EXPIRED,

    /**
     * {@code PUT /api/users/me/password}: la contraseña actual enviada no es la
     * de la cuenta (400, con el detalle en {@code validationErrors.currentPassword}).
     *
     * <p>
     * Es un 400 y no un 401 a propósito: el usuario <b>sí</b> está autenticado
     * (su sesión es válida); lo incorrecto es un dato del formulario. Un 401
     * haría que el frontend intentara renovar la sesión y, al repetirse, la
     * cerrara. Tampoco es un 403 (falta de permisos). Tiene código propio, y no
     * {@link #VALIDATION_ERROR}, porque no es un fallo de formato: el cliente
     * puede tratarlo aparte (por ejemplo, vaciar solo ese campo).
     * </p>
     */
    CURRENT_PASSWORD_INCORRECT,

    /*
     * Aquí estuvo EMAIL_NOT_VERIFIED (403 en el login con la contraseña
     * correcta de una cuenta pendiente de confirmar el correo). Se retiró antes
     * de publicarse (incidencia QA-1): quien se registraba con un email ajeno y
     * su propia contraseña recibía 403 si el email era nuevo y 401 si ya tenía
     * cuenta, así que delataba qué emails están registrados. Ahora ese login
     * responde el 401 INVALID_CREDENTIALS de cualquier fallo (ver
     * AuthenticationService#isUsableForLogin).
     */

    /**
     * {@code POST /api/auth/email-verification}: el token del enlace no sirve
     * (400). No existe, ha caducado, ya se usó, se invalidó al pedir otro
     * correo o no tiene el formato de un token.
     *
     * <p>
     * Un único código y un único mensaje para todos esos casos, a propósito:
     * el usuario tiene que hacer lo mismo en todos (pedir otro enlace) y
     * distinguirlos solo ayudaría a quien prueba tokens. Es un 400 y no un 404
     * o un 401 porque lo que falla es el dato enviado, no una ruta ni una
     * sesión.
     * </p>
     */
    INVALID_VERIFICATION_TOKEN,

    /**
     * {@code POST /api/auth/password-reset/confirm}: el token del enlace de
     * «He olvidado mi contraseña» no sirve (400). No existe, ha caducado, ya
     * se usó, se invalidó al pedir otro enlace (o al cambiar la contraseña) o
     * no tiene el formato de un token.
     *
     * <p>
     * Distinto de {@link #INVALID_VERIFICATION_TOKEN} porque la interfaz ofrece
     * otra salida (pedir otro enlace de restablecimiento, no de confirmación).
     * Igual que aquel, un único código y un único mensaje para todos los casos.
     * Si el token vale pero la contraseña nueva no cumple la política, la
     * respuesta es {@link #VALIDATION_ERROR} en {@code newPassword} y el token
     * <b>no</b> se gasta.
     * </p>
     */
    INVALID_RESET_TOKEN
}
