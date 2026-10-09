package com.emilio.streambox.security.refresh;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de los refresh tokens ({@code streambox.auth.refresh.*}).
 *
 * <p>
 * Mismo prefijo que la cookie ({@code streambox.auth.cookie.*}): todo lo de la
 * sesión del navegador cuelga de {@code streambox.auth}. Lo del JWT de acceso
 * sigue en {@code jwt.*}. Variables de entorno equivalentes (enlace relajado
 * de Spring Boot): {@code STREAMBOX_AUTH_REFRESH_TTL},
 * {@code STREAMBOX_AUTH_REFRESH_FAMILY_TTL}, etc.
 * </p>
 *
 * <p>
 * Se validan en los constructores compactos (como {@code JwtProperties}): un
 * valor absurdo impide arrancar con un mensaje que nombra la propiedad.
 * </p>
 *
 * @param ttl        vida de cada refresh token (7 días por defecto). Al rotar,
 *                   el nuevo vive esto desde la rotación, sin pasar del tope de
 *                   la familia
 * @param familyTtl  tope absoluto de una sesión desde el login (30 días por
 *                   defecto): por mucho que se rote, a los 30 días hay que
 *                   volver a escribir la contraseña. Debe ser {@code >= ttl}
 * @param reuseGrace margen tras una rotación en el que volver a presentar el
 *                   token viejo no se considera robo (10 s por defecto): dos
 *                   pestañas que refrescan a la vez. Entre 0 (sin gracia) y 1
 *                   minuto: una gracia larga daría a un ladrón más tiempo para
 *                   usar un token copiado sin ser detectado
 * @param cleanup    limpieza periódica de tokens que ya no sirven
 */
@ConfigurationProperties(prefix = "streambox.auth.refresh")
public record RefreshTokenProperties(
        @DefaultValue("7d") Duration ttl,
        @DefaultValue("30d") Duration familyTtl,
        @DefaultValue("10s") Duration reuseGrace,
        @DefaultValue Cleanup cleanup) {

    /** Máximo admitido para {@link #reuseGrace()}. */
    public static final Duration MAX_REUSE_GRACE = Duration.ofMinutes(1);

    /**
     * Comprueba la coherencia de las duraciones.
     *
     * @throws IllegalArgumentException si alguna falta o no es coherente
     */
    public RefreshTokenProperties {
        if (ttl == null || !isPositive(ttl)) {
            throw new IllegalArgumentException(
                    "streambox.auth.refresh.ttl debe ser mayor que 0 (por ejemplo 7d)");
        }
        if (familyTtl == null || familyTtl.compareTo(ttl) < 0) {
            throw new IllegalArgumentException(
                    "streambox.auth.refresh.family-ttl debe ser mayor o igual que streambox.auth.refresh.ttl "
                            + "(por ejemplo 30d)");
        }
        if (reuseGrace == null || reuseGrace.isNegative() || reuseGrace.compareTo(MAX_REUSE_GRACE) > 0) {
            throw new IllegalArgumentException(
                    "streambox.auth.refresh.reuse-grace debe estar entre 0s y 1m (por ejemplo 10s)");
        }
        if (cleanup == null) {
            throw new IllegalArgumentException("streambox.auth.refresh.cleanup es obligatorio");
        }
    }

    private static boolean isPositive(Duration duration) {
        return !duration.isZero() && !duration.isNegative();
    }

    /**
     * Limpieza periódica ({@code streambox.auth.refresh.cleanup.*}), ver
     * {@link RefreshTokenCleanupConfig}.
     *
     * @param enabled      si se programa la tarea (por defecto sí; el perfil
     *                     {@code test} la desactiva para que no borre filas a
     *                     mitad de un test)
     * @param interval     tiempo entre el final de una limpieza y el inicio de
     *                     la siguiente (1 h por defecto)
     * @param initialDelay espera tras el arranque antes de la primera (1 min
     *                     por defecto, para no sumar trabajo al arranque)
     * @param retention    cuánto se conservan los tokens ya caducados de una
     *                     familia aún viva (3 días por defecto). Son los que
     *                     delatan una reutilización: si se borrasen en cuanto
     *                     caducan, presentar uno copiado daría un simple 401
     *                     «no existe» en lugar de revocar la familia
     */
    public record Cleanup(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1h") Duration interval,
            @DefaultValue("1m") Duration initialDelay,
            @DefaultValue("3d") Duration retention) {

        /**
         * Comprueba las duraciones de la limpieza.
         *
         * @throws IllegalArgumentException si alguna falta o es negativa
         */
        public Cleanup {
            if (interval == null || !isPositive(interval)) {
                throw new IllegalArgumentException(
                        "streambox.auth.refresh.cleanup.interval debe ser mayor que 0 (por ejemplo 1h)");
            }
            if (initialDelay == null || initialDelay.isNegative()) {
                throw new IllegalArgumentException(
                        "streambox.auth.refresh.cleanup.initial-delay no puede ser negativo");
            }
            if (retention == null || retention.isNegative()) {
                throw new IllegalArgumentException(
                        "streambox.auth.refresh.cleanup.retention no puede ser negativo (por ejemplo 3d)");
            }
        }
    }
}
