package com.emilio.streambox.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * Cambios que el usuario autenticado puede hacer en su propio perfil
 * ({@code PATCH /api/users/me}). Hoy solo el nombre de usuario.
 *
 * <p>
 * <strong>El email no se puede cambiar</strong>: es la identidad con la que
 * se inicia sesión y el {@code subject} del JWT, así que cambiarlo cerraría la
 * sesión y abriría la puerta a robos de cuenta si no se verifica el correo
 * nuevo. Tampoco el rol (lo decide el servidor) ni la contraseña (necesitaría
 * la actual y la política de contraseñas, con su propio endpoint).
 * </p>
 *
 * <p>
 * <strong>Por qué {@code email}, {@code role} y {@code password} aparecen
 * aquí.</strong> Jackson ignora las propiedades desconocidas, de modo que un
 * cliente que enviara {@code "email"} recibiría un 200 y creería que lo ha
 * cambiado. Declararlas con {@code @Null} convierte ese malentendido en un 400
 * {@code VALIDATION_ERROR} con un mensaje por campo que explica que no se
 * puede. Son de tipo {@link Object} para aceptar cualquier valor JSON (un
 * objeto o un número también se rechazan con el mismo mensaje, no con un
 * {@code MALFORMED_REQUEST}) y van ocultas en OpenAPI porque no forman parte
 * del contrato. Enviarlas a {@code null} no cambia nada y se acepta. Cualquier
 * otra propiedad desconocida se ignora: el servicio solo lee
 * {@link #username()}, así que nunca puede cambiar otra cosa.
 * </p>
 *
 * <p>
 * <strong>Por qué {@code @NotNull} y no {@code @NotBlank}.</strong> Con
 * {@code @NotBlank} un nombre vacío incumpliría dos restricciones a la vez
 * (también {@code @Size}) y el mensaje dependería del orden, que Bean
 * Validation no garantiza. Un nombre de solo espacios (o de espacios duros)
 * lo rechaza el servicio, que vuelve a medir la longitud tras normalizarlo.
 * </p>
 *
 * @param username nuevo nombre de usuario (obligatorio, entre 3 y 50 caracteres)
 * @param email    no se admite: debe omitirse o ser {@code null}
 * @param role     no se admite: debe omitirse o ser {@code null}
 * @param password no se admite: debe omitirse o ser {@code null}
 */
public record UpdateProfileRequest(

        @Schema(description = "Nuevo nombre de usuario. Se quitan los espacios (y caracteres invisibles) "
                + "de los extremos y los interiores repetidos se reducen a uno; tras ello debe tener "
                + "entre 3 y 50 caracteres y no estar en uso por otra cuenta.", example = "cinefila_88")
        @NotNull(message = USERNAME_REQUIRED_MESSAGE)
        @Size(min = USERNAME_MIN_LENGTH, max = USERNAME_MAX_LENGTH, message = USERNAME_SIZE_MESSAGE)
        String username,

        @Schema(hidden = true)
        @Null(message = "El correo electrónico no se puede cambiar")
        Object email,

        @Schema(hidden = true)
        @Null(message = "El rol no se puede cambiar")
        Object role,

        @Schema(hidden = true)
        @Null(message = "La contraseña no se puede cambiar desde aquí")
        Object password) {

    /** Longitud mínima del nombre de usuario (la misma que en el registro). */
    public static final int USERNAME_MIN_LENGTH = 3;

    /**
     * Longitud máxima del nombre de usuario. Coincide con la columna
     * {@code users.username VARCHAR(50)}.
     */
    public static final int USERNAME_MAX_LENGTH = 50;

    /** Mensaje de longitud, común a {@code @Size}, al registro y al servicio. */
    public static final String USERNAME_SIZE_MESSAGE = "El nombre de usuario debe tener entre 3 y 50 caracteres";

    /** Mensaje cuando falta el nombre de usuario. */
    public static final String USERNAME_REQUIRED_MESSAGE = "El nombre de usuario es obligatorio";
}
