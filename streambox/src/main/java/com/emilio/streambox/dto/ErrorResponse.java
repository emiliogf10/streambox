package com.emilio.streambox.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Estructura única y pública de todas las respuestas de error de la API.
 *
 * <p>El campo {@link #code} es el contrato estable para los clientes. El
 * campo {@link #message} está pensado para mostrar información legible y no
 * debe usarse para tomar decisiones de negocio.</p>
 *
 * <p>Los campos opcionales ({@code validationErrors}, {@code remainingAttempts})
 * se omiten del JSON cuando valen {@code null} gracias a
 * {@code @JsonInclude(NON_NULL)}: no aparecen como {@code null} en el resto de
 * errores.</p>
 *
 * <p><b>Documentación OpenAPI.</b> Las anotaciones {@code @Schema} solo
 * describen el JSON para {@code /v3/api-docs}; no cambian la serialización
 * (no son anotaciones de Jackson). Los seis campos fijos se marcan como
 * obligatorios ({@code REQUIRED}) porque siempre se envían; los dos opcionales
 * no son obligatorios y tampoco se declaran {@code nullable}, porque cuando no
 * aplican no se envían con {@code null}: directamente no aparecen. Que todas
 * las respuestas 4xx/5xx de la API apunten a este esquema lo hace
 * {@code config.ErrorResponseOpenApiCustomizer}.</p>
 */
@Schema(description = "Formato común de todas las respuestas de error de la API (4xx y 5xx). El cliente "
        + "debe decidir por status y code, nunca por el texto de message. Los campos que no aplican a un "
        + "error se omiten del JSON: nunca se envían con valor null.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /**
     * Descripción OpenAPI de {@link #code}. El enum de valores lo genera
     * springdoc a partir de {@link ErrorCode}; aquí se explica qué significa
     * cada uno, porque el Javadoc del enum no llega al OpenAPI. Un test
     * comprueba que todos los valores de {@link ErrorCode} aparecen en este
     * texto: si se añade un código, hay que explicarlo aquí también.
     */
    private static final String CODE_DESCRIPTION =
            "Código funcional estable del error: el cliente debe decidir por este campo (y por status), "
                    + "nunca por el texto de message. Valores:\n"
                    + "- RESOURCE_NOT_FOUND (404): el recurso o la ruta no existen (una serie sin episodios "
                    + "también da este código a los usuarios).\n"
                    + "- MOVIE_NOT_IN_FAVORITES (404): la película no está en la lista del usuario.\n"
                    + "- SERIES_NOT_IN_FAVORITES (404): la serie existe, pero no está en la lista del usuario.\n"
                    + "- USER_ALREADY_EXISTS (409): el nombre de usuario o el email ya están registrados.\n"
                    + "- MOVIE_ALREADY_IN_FAVORITES (409): la película ya está en la lista del usuario.\n"
                    + "- SERIES_ALREADY_IN_FAVORITES (409): la serie ya está en la lista del usuario.\n"
                    + "- EPISODE_ALREADY_EXISTS (409): la serie ya tiene un episodio con esa temporada y "
                    + "número.\n"
                    + "- GENRE_ALREADY_EXISTS (409): ya existe otro género con ese nombre.\n"
                    + "- GENRE_IN_USE (409): el género no se puede borrar porque alguna película o serie lo "
                    + "tiene asignado.\n"
                    + "- AMBIGUOUS_TITLE (409): varias películas tienen ese título; hay que usar el id.\n"
                    + "- DATA_INTEGRITY_VIOLATION (409): la operación choca con una restricción de los datos.\n"
                    + "- VALIDATION_ERROR (400): datos o parámetros no válidos; el detalle por campo va en "
                    + "validationErrors.\n"
                    + "- MALFORMED_REQUEST (400 y otros 4xx genéricos): la petición no se puede interpretar "
                    + "(JSON mal formado, tipos incorrectos).\n"
                    + "- INVALID_CREDENTIALS (401): email o contraseña incorrectos en el login, o token "
                    + "ausente, caducado o no válido.\n"
                    + "- ACCESS_DENIED (403): autenticado, pero sin permisos (por ejemplo, no es ADMIN).\n"
                    + "- METHOD_NOT_ALLOWED (405): el método HTTP no está soportado por la ruta.\n"
                    + "- UNSUPPORTED_MEDIA_TYPE (415): el tipo de contenido no está soportado.\n"
                    + "- RATE_LIMIT_EXCEEDED (429): demasiadas peticiones de login o registro desde la misma "
                    + "IP; ver la cabecera Retry-After.\n"
                    + "- ACCOUNT_LOCKED (429): la cuenta está bloqueada temporalmente por logins fallidos; "
                    + "ver la cabecera Retry-After.\n"
                    + "- INTERNAL_ERROR (500 u otro 5xx): error no controlado en el servidor.";

    @Schema(description = "Fecha y hora del error, en UTC y formato ISO-8601.",
            example = "2026-10-04T11:15:30.123Z", requiredMode = Schema.RequiredMode.REQUIRED)
    private final Instant timestamp;

    @Schema(description = "Código de estado HTTP; coincide con el de la respuesta.",
            example = "404", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int status;

    @Schema(description = "Frase estándar del estado HTTP según la especificación (en inglés).",
            example = "Not Found", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String error;

    @Schema(description = CODE_DESCRIPTION, example = "RESOURCE_NOT_FOUND",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final ErrorCode code;

    @Schema(description = "Mensaje legible en español, pensado para mostrarlo al usuario. Puede cambiar: "
            + "no se debe usar para tomar decisiones.",
            example = "Película no encontrada", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String message;

    @Schema(description = "Ruta de la petición que produjo el error (sin los parámetros de consulta).",
            example = "/api/movies/42", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String path;

    @Schema(description = "Solo en los 400 con código VALIDATION_ERROR: un mensaje por cada campo o "
            + "parámetro no válido (nombre del campo → mensaje). En el resto de errores no aparece "
            + "(no se envía con null).",
            example = "{\"title\": \"El título es obligatorio\"}",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private final Map<String, String> validationErrors;

    /**
     * Intentos de login que le quedan a la cuenta, con el fallo de esta
     * respuesta ya descontado (con un máximo de 5: 4, 3, 2 y 1). Si el
     * siguiente intento también falla y era el último, la cuenta se bloquea y
     * esa respuesta ya no es un 401 sino un 429 {@code ACCOUNT_LOCKED} con
     * {@code Retry-After}; por eso nunca vale 0. Solo lo lleva el 401
     * {@code INVALID_CREDENTIALS} del login (siempre &ge; 1); en cualquier
     * otro error es {@code null} y se omite.
     */
    @Schema(description = "Solo en el 401 INVALID_CREDENTIALS del login: intentos que le quedan a la cuenta, "
            + "con este fallo ya descontado. Nunca vale 0, porque el fallo que agota los intentos ya "
            + "responde 429 ACCOUNT_LOCKED. En el resto de errores no aparece (no se envía con null).",
            example = "4", minimum = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private final Integer remainingAttempts;

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
            Instant timestamp,
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
            Instant timestamp,
            int status,
            String error,
            ErrorCode code,
            String message,
            String path,
            Map<String, String> validationErrors) {

        this(timestamp, status, error, code, message, path, validationErrors, null);
    }

    /**
     * Crea una respuesta de error completa.
     *
     * @param validationErrors  errores de validación agrupados por campo (o {@code null})
     * @param remainingAttempts intentos de login restantes (solo en el 401 del
     *                          login; {@code null} en el resto)
     */
    public ErrorResponse(
            Instant timestamp,
            int status,
            String error,
            ErrorCode code,
            String message,
            String path,
            Map<String, String> validationErrors,
            Integer remainingAttempts) {

        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.code = code;
        this.message = message;
        this.path = path;
        this.validationErrors = validationErrors;
        this.remainingAttempts = remainingAttempts;
    }

    public Instant getTimestamp() { return timestamp; }
    public int getStatus() { return status; }
    public String getError() { return error; }
    public ErrorCode getCode() { return code; }
    public String getMessage() { return message; }
    public String getPath() { return path; }
    public Map<String, String> getValidationErrors() { return validationErrors; }
    public Integer getRemainingAttempts() { return remainingAttempts; }
}
