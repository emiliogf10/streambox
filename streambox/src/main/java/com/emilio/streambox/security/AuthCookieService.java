package com.emilio.streambox.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Construye la cookie de sesión que transporta el JWT.
 *
 * <p>
 * Atributos: {@code HttpOnly} (el JavaScript de la página no puede leerla, así
 * que un XSS no roba el token), {@code SameSite=Strict} (el navegador no la
 * envía en peticiones originadas desde otros sitios), {@code Path=/api} (solo
 * viaja a la API) y {@code Max-Age} igual a la vida del JWT (la cookie
 * caduca a la vez que el token). {@code Secure} depende de
 * {@link AuthCookieProperties}.
 * </p>
 */
@Component
public class AuthCookieService {

    /** Nombre de la cookie de sesión. */
    public static final String COOKIE_NAME = "streambox_token";

    /** Ruta a la que se limita la cookie. */
    public static final String COOKIE_PATH = "/api";

    private final boolean secure;

    private final Duration maxAge;

    /**
     * Crea el servicio.
     *
     * @param cookieProperties propiedades de la cookie
     * @param jwtProperties    propiedades del JWT (de ahí sale la duración)
     */
    public AuthCookieService(AuthCookieProperties cookieProperties, JwtProperties jwtProperties) {
        this.secure = cookieProperties.secure();
        this.maxAge = Duration.ofHours(jwtProperties.expirationHours());
    }

    /**
     * Cookie de sesión con el token.
     *
     * @param token JWT recién emitido
     * @return valor de la cabecera {@code Set-Cookie}
     */
    public String sessionCookie(String token) {
        return base(token).maxAge(maxAge).build().toString();
    }

    /**
     * Cookie que borra la sesión: mismos atributos y {@code Max-Age=0}, que es
     * lo que exige el navegador para reconocerla como la misma cookie.
     *
     * @return valor de la cabecera {@code Set-Cookie}
     */
    public String clearingCookie() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }
}
