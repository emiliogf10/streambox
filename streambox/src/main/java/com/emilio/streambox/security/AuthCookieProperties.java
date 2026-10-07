package com.emilio.streambox.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de la cookie de sesión ({@code streambox.auth.cookie.*}).
 *
 * @param secure si la cookie lleva el atributo {@code Secure} (el navegador
 *               solo la envía por HTTPS). Por defecto es {@code true}: es lo
 *               correcto en producción. Solo debe ponerse a {@code false} para
 *               probar en HTTP plano (p. ej. el Docker Compose en
 *               {@code http://localhost:8088}); variable de entorno
 *               {@code STREAMBOX_AUTH_COOKIE_SECURE}
 */
@ConfigurationProperties(prefix = "streambox.auth.cookie")
public record AuthCookieProperties(@DefaultValue("true") boolean secure) {
}
