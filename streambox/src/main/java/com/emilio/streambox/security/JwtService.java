package com.emilio.streambox.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.MacAlgorithm;

/**
 * Servicio encargado de la generación y validación de los tokens de acceso
 * (JWT) que autentican a los usuarios de Streambox.
 *
 * <p>
 * Los tokens contienen el correo electrónico del usuario como
 * {@code subject}, identifican a Streambox como emisor ({@code iss}), duran
 * {@code jwt.access-token-ttl} (15 minutos por defecto) y están firmados con
 * HMAC-SHA256 y una clave derivada de {@code jwt.secret}. Llevan también
 * la versión de tokens de la cuenta (claim {@value #TOKEN_VERSION_CLAIM}).
 * </p>
 *
 * <h2>Revocación inmediata: claim {@code ver}</h2>
 * <p>
 * Un JWT de acceso no se guarda en ningún sitio, así que no se puede borrar.
 * Para poder anularlos antes de que caduquen, cada token lleva la versión
 * ({@code users.token_version}) que tenía la cuenta al emitirlo, y
 * {@code JwtAuthenticationFilter}, que ya lee el usuario de la base de datos
 * en cada petición, solo lo acepta si coincide con la actual. Revocar todos
 * los JWT de una cuenta es sumar 1 a esa columna, cosa que hace
 * {@code RefreshTokenService.revokeAllSessions} (cerrar sesión en todos los
 * dispositivos, cambiar o restablecer la contraseña, confirmar el correo).
 * Sin lista negra y sin consultas extra. Lo que no permite es anular un solo
 * token (el logout normal revoca su refresh, no su JWT de acceso): por eso el
 * acceso sigue durando minutos.
 * </p>
 *
 * <h2>Algoritmo fijo: HS256</h2>
 * <p>
 * Antes la clave se creaba con {@code Keys.hmacShaKeyFor(...)}, que elige
 * HS256, HS384 o HS512 según la longitud del secreto, y el parser aceptaba
 * cualquiera de los algoritmos que soporta jjwt según lo que dijera la
 * cabecera {@code alg} del token, que controla el cliente. No era explotable
 * (todos los HMAC usan la misma clave secreta y jjwt rechaza {@code none} y
 * las confusiones con claves públicas), pero el algoritmo dependía de un
 * detalle de configuración y el servidor dejaba que el token eligiera. Ahora
 * se firma siempre con HS256 y el parser solo conoce HS256: un token con otro
 * {@code alg} se rechaza con una {@code JwtException} (401). Es la
 * recomendación general para JWT (RFC 8725, sección 3.1): el verificador fija
 * el algoritmo, no lo lee del token.
 * </p>
 *
 * <p>
 * La configuración llega ya validada mediante {@link JwtProperties}; la clave
 * y el parser (inmutable y seguro entre hilos) se construyen una sola vez.
 * El tiempo sale del {@link Clock} de la aplicación, tanto al emitir como al
 * comprobar la caducidad, para que los tests puedan adelantar el reloj.
 * </p>
 */
@Service
public class JwtService {

    /** Valor del claim {@code iss} de los tokens emitidos y aceptados. */
    public static final String ISSUER = "streambox";

    /** Único algoritmo con el que se firman y se aceptan los tokens. */
    public static final MacAlgorithm ALGORITHM = Jwts.SIG.HS256;

    /**
     * Nombre del claim con la versión de tokens de la cuenta
     * ({@code users.token_version}). Corto porque viaja en cada petición.
     */
    public static final String TOKEN_VERSION_CLAIM = "ver";

    private final SecretKey signingKey;

    private final Duration accessTokenTtl;

    private final Clock clock;

    private final JwtParser parser;

    /**
     * Crea el servicio con el reloj del sistema (para tests unitarios).
     *
     * @param properties propiedades {@code jwt.*} de la aplicación
     */
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * Crea el servicio a partir de la configuración validada.
     *
     * @param properties propiedades {@code jwt.*} de la aplicación
     * @param clock      reloj de la aplicación (bean de {@code SecurityConfig})
     */
    @Autowired
    public JwtService(JwtProperties properties, Clock clock) {

        // Clave declarada como HmacSHA256 (no la que elegiría hmacShaKeyFor por
        // longitud). jjwt exige al menos 256 bits para HS256, que es justo el
        // mínimo de JwtProperties.
        this.signingKey = new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.accessTokenTtl = properties.accessTokenTtl();
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant()))
                // Sustituye la lista de algoritmos admitidos por defecto
                // (HS256/384/512, RS*, ES*, PS*, EdDSA) por solo HS256.
                .sig().clear().add(ALGORITHM).and()
                .build();
    }

    /**
     * Genera un token de acceso con la versión de tokens que trae la entidad.
     *
     * <p>
     * <b>Solo para tests</b> (decenas de ellos crean un usuario y piden su
     * token así). El código de producción usa siempre
     * {@link #generateToken(User, int)} con la versión elegida a propósito, y
     * {@code ExplicitTokenVersionSourceTest} falla si alguna clase de
     * {@code src/main} llama a esta variante.
     * </p>
     *
     * <p>
     * <b>Por qué.</b> {@code user.getTokenVersion()} es lo que haya en memoria,
     * y puede estar desfasado sin que se note: tras
     * {@code UserRepository.incrementTokenVersion} en la misma transacción la
     * entidad conserva la versión antigua (el token nacería revocado y el
     * usuario se echaría a sí mismo, el error que se evitó en el cambio de
     * contraseña), y en el refresh importa <i>cuándo</i> se leyó (BAJA-A, ver
     * {@code RefreshTokenService.refresh}). Obligar a pasarla hace que quien
     * emite tenga que pensar de dónde sale. Se conserva en vez de moverla a
     * una utilidad de tests para no reescribir esos tests sin ganar nada: el
     * test de arriba ya impide su uso en producción.
     * </p>
     *
     * @param user usuario autenticado para el que se generará el token
     * @return token JWT firmado con HS256, válido durante {@code jwt.access-token-ttl}
     */
    public String generateToken(User user) {
        return generateToken(user, user.getTokenVersion());
    }

    /**
     * Genera un token de acceso para un usuario autenticado con una versión de
     * tokens explícita (claim {@value #TOKEN_VERSION_CLAIM}).
     *
     * @param user         usuario autenticado (solo se usa su email)
     * @param tokenVersion versión actual de {@code users.token_version} de la cuenta
     * @return token JWT firmado con HS256, válido durante {@code jwt.access-token-ttl}
     */
    public String generateToken(User user, int tokenVersion) {

        Instant now = clock.instant();

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getEmail())
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey, ALGORITHM)
                .compact();
    }

    /**
     * Valida un token de acceso y devuelve su email ({@code subject}) y su
     * versión ({@value #TOKEN_VERSION_CLAIM}).
     *
     * <p>
     * Se verifica el algoritmo (solo HS256), la firma, la fecha de expiración
     * (con el reloj de la aplicación) y que el emisor sea Streambox. Si el
     * token está manipulado, ha expirado, usa otro algoritmo o fue emitido por
     * otro sistema, la librería JWT lanza una {@code JwtException}.
     * </p>
     *
     * <p>
     * Además el claim {@value #TOKEN_VERSION_CLAIM} debe existir y ser un
     * entero JSON entre {@code 0} y {@link Integer#MAX_VALUE}; si no,
     * {@link InvalidTokenVersionException} (también una {@code JwtException},
     * así que el filtro lo trata como cualquier token no válido: petición
     * anónima, nunca un 500). Un token sin {@code ver} es uno emitido antes de
     * que existiera la revocación (como mucho 15 minutos tras el despliegue): el
     * frontend, ante el 401, renueva con el refresh y recibe uno nuevo.
     * Tratarlo como «versión 0» también sería seguro (la primera revocación lo
     * anularía), pero dejaría para siempre una rama de compatibilidad en el
     * parser; rechazarlo deja una sola regla (todo token válido lleva su
     * versión) y al usuario solo le cuesta una renovación transparente.
     * </p>
     *
     * <p>
     * <b>Por qué se comprueba el tipo a mano</b> en vez de con
     * {@code claims.get(name, Integer.class)}: las conversiones de jjwt entre
     * tipos numéricos son un detalle de la librería. Aquí la regla es explícita:
     * {@code Integer} o {@code Long} (lo que produce Jackson para un entero
     * JSON) dentro de rango; un texto ({@code "1"}), un decimal ({@code 1.0},
     * que llega como {@code Double}), un booleano, un negativo o un número que
     * no cabe en un {@code int} se rechazan. Ninguno lo emite este servicio,
     * así que solo puede venir de un token firmado a mano con el secreto.
     * </p>
     *
     * @param token token JWT recibido (cabecera o cookie)
     * @return email y versión del token
     * @throws io.jsonwebtoken.JwtException si el token no es válido (incluido
     *         {@link InvalidTokenVersionException} si falta {@code ver} o no
     *         tiene el tipo esperado)
     * @throws IllegalArgumentException si el token está vacío (lo lanza jjwt)
     */
    public AccessTokenClaims parseAccessToken(String token) {

        Claims claims = parser.parseSignedClaims(token).getPayload();
        return new AccessTokenClaims(claims.getSubject(), readTokenVersion(claims.get(TOKEN_VERSION_CLAIM)));
    }

    /**
     * Convierte el valor crudo del claim {@value #TOKEN_VERSION_CLAIM} en la
     * versión, o lanza {@link InvalidTokenVersionException}.
     */
    private static int readTokenVersion(Object raw) {

        long value;
        if (raw instanceof Integer integer) {
            value = integer;
        } else if (raw instanceof Long longValue) {
            value = longValue;
        } else {
            // null (falta), String, Double, BigInteger, Boolean, objeto, lista...
            throw new InvalidTokenVersionException();
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new InvalidTokenVersionException();
        }
        return (int) value;
    }

    /**
     * El claim {@value #TOKEN_VERSION_CLAIM} falta o no es un entero válido.
     *
     * <p>
     * Hereda de {@link JwtException} a propósito: es el contrato del filtro
     * ({@code JwtAuthenticationFilter} solo perdona {@code JwtException} e
     * {@code IllegalArgumentException}), así que no hace falta tocarlo y un
     * claim raro nunca puede acabar en un 500. El mensaje es fijo y no
     * reproduce el valor del token.
     * </p>
     */
    public static final class InvalidTokenVersionException extends JwtException {

        private static final long serialVersionUID = 1L;

        InvalidTokenVersionException() {
            super("El claim " + TOKEN_VERSION_CLAIM + " falta o no es un entero válido");
        }
    }
}
