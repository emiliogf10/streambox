package com.emilio.streambox.security;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.emilio.streambox.security.refresh.IssuedRefreshToken;

/**
 * Construye las dos cookies de la sesión del navegador.
 *
 * <ul>
 *   <li>{@value #COOKIE_NAME}: el JWT de acceso. {@code Path=/api} (viaja a
 *       toda la API) y {@code Max-Age} igual a la vida del JWT
 *       ({@code jwt.access-token-ttl}): al caducar, el navegador la descarta y
 *       la siguiente petición da 401, que el frontend resuelve con un
 *       refresh.</li>
 *   <li>{@value #REFRESH_COOKIE_NAME}: el refresh token.
 *       {@code Path=/api/auth}: solo viaja al login, al refresh y al logout,
 *       nunca al resto de la API, así que no aparece en los logs ni en las
 *       peticiones normales. {@code Max-Age} = la vida que le queda al
 *       token.</li>
 * </ul>
 *
 * <p>
 * Ambas son {@code HttpOnly} (el JavaScript de la página no puede leerlas, así
 * que un XSS no roba los tokens), {@code SameSite=Strict} (el navegador no las
 * envía en peticiones originadas desde otros sitios) y {@code Secure} según
 * {@link AuthCookieProperties}.
 * </p>
 */
@Component
public class AuthCookieService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthCookieService.class);

    /** Nombre de la cookie con el JWT de acceso. */
    public static final String COOKIE_NAME = "streambox_token";

    /** Ruta a la que se limita la cookie del JWT de acceso. */
    public static final String COOKIE_PATH = "/api";

    /** Nombre de la cookie con el refresh token. */
    public static final String REFRESH_COOKIE_NAME = "streambox_refresh";

    /** Ruta a la que se limita la cookie del refresh token. */
    public static final String REFRESH_COOKIE_PATH = "/api/auth";

    /** Aviso de arranque con {@code Secure} desactivado en {@code prod}. */
    static final String INSECURE_IN_PROD_WARNING =
            "streambox.auth.cookie.secure=false con el perfil prod: las cookies de sesión "
                    + "(" + COOKIE_NAME + " y " + REFRESH_COOKIE_NAME + ") se enviarán también por HTTP sin "
                    + "cifrar y cualquiera en la red podría copiarlas. Si la aplicación se sirve por HTTPS, "
                    + "pon STREAMBOX_AUTH_COOKIE_SECURE=true. Solo es aceptable en una prueba local por HTTP "
                    + "(p. ej. docker compose en http://localhost:8088)";

    private final boolean secure;

    private final Duration accessMaxAge;

    /**
     * Crea el servicio y avisa si la configuración de producción es insegura.
     *
     * <p>
     * Solo avisa (no impide arrancar): el {@code docker-compose.yml} usa el
     * perfil {@code prod} sirviendo HTTP en localhost, donde el navegador no
     * guardaría una cookie {@code Secure}. Detrás de HTTPS, el aviso en el log
     * de arranque delata el olvido.
     * </p>
     *
     * @param cookieProperties propiedades de la cookie
     * @param jwtProperties    propiedades del JWT (de ahí sale la vida de la cookie de acceso)
     * @param environment      entorno, para saber si el perfil {@code prod} está activo
     */
    public AuthCookieService(AuthCookieProperties cookieProperties, JwtProperties jwtProperties,
            Environment environment) {
        this.secure = cookieProperties.secure();
        this.accessMaxAge = jwtProperties.accessTokenTtl();
        if (!secure && environment.acceptsProfiles(Profiles.of("prod"))) {
            LOGGER.warn(INSECURE_IN_PROD_WARNING);
        }
    }

    /**
     * Cookie con el JWT de acceso.
     *
     * @param token JWT recién emitido
     * @return valor de la cabecera {@code Set-Cookie}
     */
    public String sessionCookie(String token) {
        return base(COOKIE_NAME, COOKIE_PATH, token).maxAge(accessMaxAge).build().toString();
    }

    /**
     * Cookie que borra el JWT de acceso: mismos nombre, ruta y atributos con
     * {@code Max-Age=0}, que es lo que exige el navegador para reconocerla como
     * la misma cookie.
     *
     * @return valor de la cabecera {@code Set-Cookie}
     */
    public String clearingCookie() {
        return base(COOKIE_NAME, COOKIE_PATH, "").maxAge(Duration.ZERO).build().toString();
    }

    /**
     * Cookie con el refresh token.
     *
     * @param refreshToken token recién emitido y su vida restante
     * @return valor de la cabecera {@code Set-Cookie}
     */
    public String refreshCookie(IssuedRefreshToken refreshToken) {
        return base(REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH, refreshToken.value())
                .maxAge(refreshToken.maxAge()).build().toString();
    }

    /**
     * Cookie que borra el refresh token ({@code Max-Age=0}, misma ruta).
     *
     * @return valor de la cabecera {@code Set-Cookie}
     */
    public String clearingRefreshCookie() {
        return base(REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH, "").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String path, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path);
    }
}
