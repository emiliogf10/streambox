package com.emilio.streambox.dto;

import java.time.Instant;

import com.emilio.streambox.entity.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO con los datos públicos de un usuario que se devuelven al cliente.
 *
 * <p>
 * No incluye la contraseña (ni siquiera cifrada): ese dato nunca sale de
 * la aplicación.
 * </p>
 *
 * <p>
 * {@code avatar} sale <b>siempre</b> en el JSON, también como {@code null}
 * (Jackson escribe los nulos: no hay {@code NON_NULL} global ni en este
 * record), para que el cliente no tenga que distinguir «sin avatar» de «campo
 * que esta versión de la API no conoce». Se publica como texto libre y no con
 * la lista de {@code Avatar}: una clave que se retire de la lista sigue
 * guardada en las cuentas que la eligieron, y el cliente debe pintarla como la
 * inicial en lugar de fallar.
 * </p>
 *
 * @param id        identificador del usuario
 * @param username  nombre de usuario
 * @param email     correo electrónico
 * @param role      rol del usuario
 * @param createdAt instante en el que se creó la cuenta
 * @param avatar    clave del icono de perfil, o {@code null} si no eligió ninguno
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        Role role,
        Instant createdAt,

        @Schema(types = { "string", "null" }, description = "Clave del icono de perfil elegido (una de las de "
                + "UpdateProfileRequest.avatar), o null si no eligió ninguno: entonces se muestra la inicial del "
                + "nombre. Trátala como texto: si el cliente no conoce la clave, debe mostrar también la inicial.",
                example = "rocket")
        String avatar) {
}
