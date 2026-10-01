package com.emilio.streambox.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Propiedades de configuración de los tokens JWT ({@code jwt.*}).
 *
 * <p>
 * Se validan al arrancar la aplicación: si el secreto falta o es demasiado
 * corto, la aplicación no se inicia y el error indica la propiedad
 * responsable. Antes, un secreto débil solo se descubría al intentar firmar
 * el primer token.
 * </p>
 *
 * @param secret          secreto con el que se firman los tokens (HMAC-SHA).
 *                        Se interpreta como texto UTF-8 y debe tener al menos
 *                        32 caracteres (256 bits). Genera uno con
 *                        {@code openssl rand -base64 48}
 * @param expirationHours horas de validez de cada token (24 por defecto)
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @NotBlank(message = "jwt.secret es obligatorio (variable de entorno JWT_SECRET)")
        @Size(min = 32, message = "jwt.secret debe tener al menos 32 caracteres (256 bits)")
        String secret,

        @DefaultValue("24")
        @Positive(message = "jwt.expiration-hours debe ser mayor que 0")
        long expirationHours) {
}
