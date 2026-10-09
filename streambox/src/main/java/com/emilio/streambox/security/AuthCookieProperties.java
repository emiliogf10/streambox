package com.emilio.streambox.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de las cookies de sesión ({@code streambox.auth.cookie.*}):
 * {@code streambox_token} (JWT de acceso) y {@code streambox_refresh}
 * (refresh token).
 *
 * @param secure si las cookies llevan el atributo {@code Secure} (el navegador
 *               solo las envía por HTTPS). Por defecto es {@code true}: es lo
 *               correcto en producción. Solo debe ponerse a {@code false} para
 *               probar en HTTP plano (p. ej. el Docker Compose en
 *               {@code http://localhost:8088}); variable de entorno
 *               {@code STREAMBOX_AUTH_COOKIE_SECURE}. Con el perfil
 *               {@code prod} y {@code false}, el arranque escribe un
 *               {@code WARN} ({@link AuthCookieService})
 */
@ConfigurationProperties(prefix = "streambox.auth.cookie")
public record AuthCookieProperties(@DefaultValue("true") boolean secure) {
}
