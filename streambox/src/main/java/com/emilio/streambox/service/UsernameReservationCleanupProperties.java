package com.emilio.streambox.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limpieza periódica de las reservas de nombre caducadas
 * ({@code streambox.username-reservations.cleanup.*}), ver
 * {@link UsernameReservationCleanupConfig}. Mismo esquema que
 * {@code streambox.auth.refresh.cleanup.*}.
 *
 * <p>
 * Se valida en el constructor compacto (como {@code RefreshTokenProperties}):
 * un valor absurdo impide arrancar con un mensaje que nombra la propiedad.
 * </p>
 *
 * @param enabled      si se programa la tarea (por defecto sí; el perfil
 *                     {@code test} la desactiva para que no borre filas a mitad
 *                     de un test)
 * @param interval     tiempo entre el final de una limpieza y el inicio de la
 *                     siguiente (1 h por defecto)
 * @param initialDelay espera tras el arranque antes de la primera (1 min por
 *                     defecto, para no sumar trabajo al arranque)
 */
@ConfigurationProperties(prefix = "streambox.username-reservations.cleanup")
public record UsernameReservationCleanupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration interval,
        @DefaultValue("1m") Duration initialDelay) {

    /**
     * Comprueba las duraciones.
     *
     * @throws IllegalArgumentException si alguna falta o no es válida
     */
    public UsernameReservationCleanupProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "streambox.username-reservations.cleanup.interval debe ser mayor que 0 (por ejemplo 1h)");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "streambox.username-reservations.cleanup.initial-delay no puede ser negativo");
        }
    }
}
