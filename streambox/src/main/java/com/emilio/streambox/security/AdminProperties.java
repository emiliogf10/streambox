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
 * @param password contraseña inicial en texto plano; al crear la cuenta debe
 *                 cumplir la política de contraseñas ({@code PasswordPolicy}:
 *                 12 a 64 caracteres, máximo 72 bytes, no común y sin el
 *                 usuario ni el email). Se guarda cifrada con BCrypt. Si el
 *                 administrador ya existe no se usa ni se valida (solo se
 *                 avisa en el log si no cumple la política). Por eso aquí no
 *                 hay restricciones de Bean Validation: impedirían arrancar
 *                 también cuando la cuenta ya existe
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
