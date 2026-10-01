package com.emilio.streambox.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Datos opcionales del primer administrador ({@code streambox.admin.*}).
 *
 * <p>
 * Si {@code email} y {@code password} están definidos (variables de entorno
 * {@code ADMIN_EMAIL} y {@code ADMIN_PASSWORD}), {@link AdminAccountInitializer}
 * crea la cuenta al arrancar. Si no, no se hace nada.
 * </p>
 *
 * @param email    correo del administrador (vacío = no crear)
 * @param username nombre de usuario del administrador ({@code admin} por defecto)
 * @param password contraseña inicial en texto plano (mínimo 12 caracteres); se
 *                 guarda cifrada con BCrypt
 */
@ConfigurationProperties(prefix = "streambox.admin")
public record AdminProperties(
        String email,
        @DefaultValue("admin") String username,
        String password) {

    /** @return {@code true} si hay datos suficientes para crear el administrador */
    public boolean isConfigured() {
        return email != null && !email.isBlank()
                && password != null && !password.isBlank();
    }
}
