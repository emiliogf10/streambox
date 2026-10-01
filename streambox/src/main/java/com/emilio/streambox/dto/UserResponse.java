package com.emilio.streambox.dto;

import java.time.Instant;

import com.emilio.streambox.entity.Role;

/**
 * DTO con los datos públicos de un usuario que se devuelven al cliente.
 *
 * <p>
 * No incluye la contraseña (ni siquiera cifrada): ese dato nunca sale de
 * la aplicación.
 * </p>
 *
 * @param id        identificador del usuario
 * @param username  nombre de usuario
 * @param email     correo electrónico
 * @param role      rol del usuario
 * @param createdAt instante en el que se creó la cuenta
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        Role role,
        Instant createdAt) {
}
