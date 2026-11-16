package com.emilio.streambox.security.device;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de la cookie de dispositivo {@code streambox_device}
 * ({@code streambox.security.device-cookie.*}); ver {@link DeviceCookieService}.
 *
 * <p>
 * Va en un grupo propio y no dentro de {@code streambox.security.rate-limit.lockout}
 * porque no es un límite: es la vida de una credencial firmada. Tiene valor por
 * defecto para que ningún despliegue (local, Docker, CI) necesite una variable
 * nueva para arrancar.
 * </p>
 *
 * @param ttl vida de la cookie desde su emisión (su {@code Max-Age} y la
 *            caducidad que se comprueba al leerla). Por defecto 30 días, como
 *            las «IPs conocidas». Un login correcto la renueva. Debe ser mayor
 *            que 0 y como mucho {@value #MAX_TTL_TEXT}: los navegadores recortan
 *            a ese tope el {@code Max-Age} de cualquier cookie, así que un valor
 *            mayor haría que el servidor aceptase cookies que el navegador ya
 *            ha tirado (inofensivo, pero engañoso)
 */
@ConfigurationProperties(prefix = "streambox.security.device-cookie")
public record DeviceCookieProperties(@DefaultValue("30d") Duration ttl) {

    /** Vida máxima admitida (el tope de {@code Max-Age} de los navegadores actuales). */
    public static final Duration MAX_TTL = Duration.ofDays(400);

    /** {@link #MAX_TTL} como texto, para los mensajes y el Javadoc. */
    static final String MAX_TTL_TEXT = "400 días";

    /** Mensaje cuando la vida no es válida. */
    static final String INVALID_TTL_MESSAGE =
            "streambox.security.device-cookie.ttl debe ser mayor que 0 y como mucho " + MAX_TTL_TEXT
                    + " (por ejemplo 30d)";

    /**
     * Comprueba la vida al enlazar las propiedades (antes de servir
     * peticiones), como {@code JwtProperties}.
     *
     * @throws IllegalArgumentException si es nula, cero, negativa o mayor que {@link #MAX_TTL}
     */
    public DeviceCookieProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(INVALID_TTL_MESSAGE);
        }
    }
}
