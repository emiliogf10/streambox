package com.emilio.streambox.dto;

import com.emilio.streambox.validation.Avatar;
import com.emilio.streambox.validation.AvatarChoice;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Cambios que el usuario autenticado puede hacer en su propio perfil
 * ({@code PATCH /api/users/me}): el nombre de usuario y el icono de perfil
 * (avatar). Cada campo es opcional; <b>ausente = no cambia</b>.
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
 * {@link #username()} y el avatar, así que nunca puede cambiar otra cosa.
 * </p>
 *
 * <p>
 * <strong>Nombre de usuario.</strong> Si viene, debe tener entre 3 y 50
 * caracteres. No lleva {@code @NotBlank}: un nombre vacío incumpliría dos
 * restricciones a la vez (también {@code @Size}) y el mensaje dependería del
 * orden, que Bean Validation no garantiza. Un nombre de solo espacios (o de
 * espacios duros) lo rechaza el servicio, que vuelve a medir la longitud tras
 * normalizarlo. {@code "username": null} equivale a omitirlo: el nombre no
 * admite «borrarse», así que no hay nada que distinguir.
 * </p>
 *
 * <p>
 * <strong>Avatar: por qué es un {@link JsonNode}.</strong> Aquí sí hay tres
 * casos distintos: campo ausente (no cambia), {@code null} (quitar el avatar y
 * volver a la inicial) y una clave. Con un {@code String}, Jackson entrega
 * {@code null} en los dos primeros y no se pueden distinguir. Con
 * {@code JsonNode}, el campo ausente llega como {@code null} de Java y el
 * {@code null} JSON como un {@code NullNode} (Jackson usa valores distintos
 * para «ausente» y «nulo» en los parámetros del constructor de un record).
 * Además, un número o un objeto llegan a la validación ({@link AvatarChoice})
 * y reciben el mismo 400 {@code VALIDATION_ERROR} con
 * {@code validationErrors.avatar}, en vez de un {@code MALFORMED_REQUEST}
 * genérico. El resto del código no ve el {@code JsonNode}: usa
 * {@link #avatarSent()} y {@link #avatarKey()}.
 * </p>
 *
 * <p>
 * <strong>Cuerpo sin cambios.</strong> Si no viene ni {@code username} ni
 * {@code avatar} ({@code {}}, o solo propiedades desconocidas o mal escritas
 * como {@code "userName"}), el servicio responde 400 con
 * {@code validationErrors.username} = {@value #NO_CHANGES_MESSAGE}: un 200
 * sin hacer nada haría creer al cliente que su cambio se guardó. El mensaje
 * nombra los dos campos porque cualquiera de ellos basta (antes decía «El
 * nombre de usuario es obligatorio», que era falso desde que existe el
 * avatar). Va en {@code validationErrors.username}, y no en el
 * {@code message} general, porque en toda la API el {@code message} de un
 * {@code VALIDATION_ERROR} es fijo y lo concreto va siempre por campo; el
 * nombre es el campo que el formulario de «Editar perfil» muestra primero.
 * </p>
 *
 * @param username nuevo nombre de usuario (opcional; si viene, entre 3 y 50 caracteres)
 * @param avatar   valor JSON del avatar: ausente, {@code null} o una clave de {@link Avatar}
 * @param email    no se admite: debe omitirse o ser {@code null}
 * @param role     no se admite: debe omitirse o ser {@code null}
 * @param password no se admite: debe omitirse o ser {@code null}
 */
public record UpdateProfileRequest(

        @Schema(description = "Nuevo nombre de usuario (opcional: ausente o null = no cambia). Se quitan "
                + "los espacios (y caracteres invisibles) de los extremos y los interiores repetidos se reducen "
                + "a uno; tras ello debe tener entre 3 y 50 caracteres y no estar en uso por otra cuenta.",
                example = "cinefila_88")
        @Size(min = USERNAME_MIN_LENGTH, max = USERNAME_MAX_LENGTH, message = USERNAME_SIZE_MESSAGE)
        String username,

        // La lista de valores de OpenAPI la fija AvatarOpenApiCustomizer a partir
        // de Avatar: aquí no se puede poner sin repetir la lista. implementation
        // = String evita que springdoc documente la estructura interna de
        // JsonNode como si fuera parte del contrato (un esquema «JsonNode» con
        // sus propiedades isArray, isNull...).
        @Schema(implementation = String.class, description = AVATAR_DESCRIPTION, example = "rocket")
        @AvatarChoice
        JsonNode avatar,

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

    /** Mensaje cuando falta el nombre de usuario en el registro ({@code CreateUserRequest}). */
    public static final String USERNAME_REQUIRED_MESSAGE = "El nombre de usuario es obligatorio";

    /**
     * Mensaje del 400 cuando el cuerpo no pide ningún cambio (ni
     * {@code username} ni {@code avatar}); sale en
     * {@code validationErrors.username}.
     */
    public static final String NO_CHANGES_MESSAGE =
            "Indica al menos un cambio: el nombre de usuario o el icono de perfil";

    /** Mensaje del 400 cuando el avatar no es de la lista. */
    public static final String AVATAR_INVALID_MESSAGE = AvatarChoice.MESSAGE;

    /** Descripción OpenAPI del campo {@code avatar}. */
    public static final String AVATAR_DESCRIPTION = "Icono de perfil (opcional). Ausente = no cambia; null = "
            + "quitar el avatar y volver a la inicial del nombre; una de las claves de la lista = guardarla. "
            + "Cualquier otro valor (otro texto, mayúsculas, espacios, un número, un objeto) da 400 "
            + "VALIDATION_ERROR con validationErrors.avatar = \"" + AvatarChoice.MESSAGE + "\".";

    /**
     * Indica si la petición pide cambiar el avatar (el campo venía, aunque
     * fuera {@code null}).
     *
     * <p>
     * No empieza por {@code get}/{@code is} para que Jackson y OpenAPI no lo
     * tomen por una propiedad del JSON.
     * </p>
     *
     * @return {@code true} si el JSON traía la propiedad {@code avatar}
     */
    public boolean avatarSent() {
        return avatar != null;
    }

    /**
     * Clave de avatar pedida, ya validada.
     *
     * @return la clave, o {@code null} si se pidió quitar el avatar o no se envió
     *         (distíngalo con {@link #avatarSent()})
     */
    public String avatarKey() {
        return avatar == null || avatar.isNull() ? null : avatar.stringValue();
    }
}
