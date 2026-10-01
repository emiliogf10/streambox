package com.emilio.streambox.security.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Límites contra el abuso de los endpoints públicos
 * ({@code streambox.security.rate-limit.*}). Los valores por defecto están en
 * {@code application.properties}.
 *
 * @param login    límite de intentos de login por IP
 * @param register límite de registros de usuario por IP
 * @param lockout  bloqueo temporal de una cuenta tras varios logins fallidos
 */
@Validated
@ConfigurationProperties(prefix = "streambox.security.rate-limit")
public record RateLimitProperties(
        @Valid @NotNull Rule login,
        @Valid @NotNull Rule register,
        @Valid @NotNull Lockout lockout) {

    /**
     * @param maxRequests peticiones permitidas por IP dentro de la ventana
     * @param window      duración de la ventana
     */
    public record Rule(@Positive int maxRequests, @NotNull Duration window) {
    }

    /**
     * @param maxFailures logins fallidos permitidos antes de bloquear la cuenta
     * @param window      ventana en la que se cuentan los fallos (y duración
     *                    máxima del bloqueo)
     */
    public record Lockout(@Positive int maxFailures, @NotNull Duration window) {
    }
}
