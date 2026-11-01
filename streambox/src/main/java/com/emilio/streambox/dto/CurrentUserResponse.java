package com.emilio.streambox.dto;

import java.time.Instant;

import com.emilio.streambox.entity.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO de la <strong>propia</strong> cuenta: lo que devuelven
 * {@code GET /api/users/me} y {@code PATCH /api/users/me}.
 *
 * <p>
 * <b>Por qué no es {@link UserResponse}.</b> Tiene sus mismos datos públicos más
 * los que solo le interesan al propio usuario, como
 * {@code genresOnboardingPending}. {@code UserResponse} es también lo que ve un
 * administrador en el listado de cuentas ({@code GET /api/users}), y ahí ese
 * dato no pinta nada: es un detalle de la interfaz de cada usuario, no
 * información de gestión. Separar los dos DTO deja claro en el contrato qué
 * sale en cada sitio y permite añadir aquí otros datos «solo para mí» sin
 * filtrarlos al panel. {@code PATCH /api/users/me} devuelve también este tipo
 * porque promete «el usuario completo, igual que {@code GET /api/users/me}» y
 * el frontend sustituye con él su copia del usuario: con {@code UserResponse}
 * perdería el campo tras cambiar el nombre o el avatar.
 * </p>
 *
 * <p>
 * Como en {@link UserResponse}, {@code avatar} sale siempre (también como
 * {@code null}) y nunca hay contraseña.
 * </p>
 *
 * @param id                      identificador del usuario
 * @param username                nombre de usuario
 * @param email                   correo electrónico
 * @param role                    rol del usuario
 * @param createdAt               instante en el que se creó la cuenta
 * @param avatar                  clave del icono de perfil, o {@code null} si no eligió ninguno
 * @param genresOnboardingPending {@code true} si hay que hacerle la pregunta de
 *                                bienvenida de los géneros favoritos (ver
 *                                {@code UserMapper.toCurrentUserResponse})
 */
public record CurrentUserResponse(
        Long id,
        String username,
        String email,
        Role role,
        Instant createdAt,

        @Schema(types = { "string", "null" }, description = "Clave del icono de perfil elegido (una de las de "
                + "UpdateProfileRequest.avatar), o null si no eligió ninguno: entonces se muestra la inicial del "
                + "nombre. Trátala como texto: si el cliente no conoce la clave, debe mostrar también la inicial.",
                example = "rocket")
        String avatar,

        @Schema(description = "true si hay que hacerle la pregunta de bienvenida de los géneros favoritos "
                + "(«¿qué géneros te gustan?»): solo una cuenta USER que aún no la ha contestado ni saltado. "
                + "Un ADMIN nunca la tiene pendiente, y las cuentas que existían antes de esta funcionalidad "
                + "tampoco. Pasa a false para siempre con el primer PUT /api/users/me/genres, también con la "
                + "lista vacía («Ahora no»).", example = "false")
        boolean genresOnboardingPending) {
}
