package com.emilio.streambox.exception;

import org.springframework.http.HttpStatus;

import com.emilio.streambox.dto.ErrorCode;

/**
 * Código y mensaje genéricos de la API para un estado HTTP de error cuando no
 * hay una excepción de dominio que diga algo más concreto.
 *
 * <p>
 * <b>Por qué existe.</b> Hay dos sitios que solo conocen el estado HTTP de un
 * error y tienen que traducirlo al {@link com.emilio.streambox.dto.ErrorResponse
 * ErrorResponse} de la API: {@link GlobalExceptionHandler#handleExceptionInternal}
 * (las excepciones estándar de Spring MVC: ruta inexistente, 405, 415, 406...) y
 * {@link ApiErrorController} (el despacho de error de Tomcat, tras un
 * {@code sendError} o una excepción que escapa de un filtro). Si cada uno
 * tuviera su propia tabla, un mismo 404 podría acabar con dos {@code code}
 * distintos según por dónde llegara; con una sola tabla el cliente ve siempre
 * lo mismo.
 * </p>
 *
 * <p>
 * Los mensajes son fijos y genéricos a propósito: nunca se usa el mensaje de la
 * excepción ni el de {@code sendError}, que pueden revelar detalles internos.
 * Los de 401 y 403 son los mismos que escriben {@code JwtAuthenticationEntryPoint}
 * y {@code JwtAccessDeniedHandler}, para que el cliente no note por qué camino
 * le llegó el error.
 * </p>
 *
 * @param code    código funcional estable del error
 * @param message mensaje en español para el cliente
 */
record GenericHttpError(ErrorCode code, String message) {

    /** Mensaje de los 4xx sin una traducción más concreta (y de los 400 de formato). */
    static final String MALFORMED_MESSAGE =
            "La petición no se puede interpretar. Revisa el formato de los datos";

    /** Mensaje de cualquier 5xx: nunca se cuenta al cliente qué falló por dentro. */
    static final String INTERNAL_ERROR_MESSAGE =
            "Se ha producido un error interno. Inténtalo de nuevo más tarde";

    /** Mensaje del 406: el cliente pidió en {@code Accept} un formato distinto de JSON. */
    static final String NOT_ACCEPTABLE_MESSAGE =
            "El formato de respuesta solicitado (cabecera Accept) no está disponible: la API responde en JSON";

    /**
     * Traduce un estado HTTP de error a su código y mensaje genéricos.
     *
     * <p>
     * Cualquier 5xx es {@code INTERNAL_ERROR}. Los 4xx sin una entrada propia
     * (400, 413...) caen en {@code MALFORMED_REQUEST}: todos significan «la
     * petición, tal como llegó, no se puede atender».
     * </p>
     *
     * <p>
     * <b>Ojo: «sin entrada propia» incluye estados que no son de formato</b>,
     * como 409, 410 o 429: por aquí saldrían como {@code MALFORMED_REQUEST} con
     * el mensaje de formato. Es aceptable porque hoy ninguno llega por este
     * camino: la API los produce siempre con una excepción de dominio que
     * {@link GlobalExceptionHandler} traduce a su código concreto
     * ({@code USER_ALREADY_EXISTS}, {@code RATE_LIMIT_EXCEEDED},
     * {@code ACCOUNT_LOCKED}...), y los 429 del límite por IP los escribe
     * {@code RateLimitingFilter} directamente, sin {@code sendError}. Además,
     * el frontend decide primero por {@code status}, que siempre es el real. Si
     * algún día uno de esos estados llegara aquí con frecuencia (un
     * {@code sendError(429)} de un filtro de terceros, por ejemplo), conviene
     * darle su propia entrada en vez de dejarlo en el genérico.
     * </p>
     *
     * @param status estado HTTP de la respuesta (4xx o 5xx)
     * @return código y mensaje que se envían al cliente
     */
    static GenericHttpError forStatus(HttpStatus status) {
        if (status.is5xxServerError()) {
            return new GenericHttpError(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
        }
        return switch (status) {
            case UNAUTHORIZED -> new GenericHttpError(ErrorCode.INVALID_CREDENTIALS,
                    "Autenticación requerida. Proporciona un token JWT válido.");
            case FORBIDDEN -> new GenericHttpError(ErrorCode.ACCESS_DENIED,
                    "No tienes permisos suficientes para acceder a este recurso.");
            case NOT_FOUND -> new GenericHttpError(ErrorCode.RESOURCE_NOT_FOUND,
                    "El recurso solicitado no existe");
            case METHOD_NOT_ALLOWED -> new GenericHttpError(ErrorCode.METHOD_NOT_ALLOWED,
                    "El método HTTP no está permitido para este recurso");
            case UNSUPPORTED_MEDIA_TYPE -> new GenericHttpError(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "El tipo de contenido no está soportado");
            case NOT_ACCEPTABLE -> new GenericHttpError(ErrorCode.NOT_ACCEPTABLE, NOT_ACCEPTABLE_MESSAGE);
            default -> new GenericHttpError(ErrorCode.MALFORMED_REQUEST, MALFORMED_MESSAGE);
        };
    }
}
