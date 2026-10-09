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
 * HMAC-SHA256 y una clave derivada de {@code jwt.secret}.
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
     * Genera un token de acceso para un usuario autenticado.
     *
     * @param user usuario autenticado para el que se generará el token
     * @return token JWT firmado con HS256, válido durante {@code jwt.access-token-ttl}
     */
    public String generateToken(User user) {

        Instant now = clock.instant();

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey, ALGORITHM)
                .compact();
    }

    /**
     * Extrae el correo electrónico almacenado como {@code subject}
     * dentro de un token JWT.
     *
     * <p>
     * Se verifica el algoritmo (solo HS256), la firma, la fecha de expiración
     * (con el reloj de la aplicación) y que el emisor sea Streambox. Si el
     * token está manipulado, ha expirado, usa otro algoritmo o fue emitido por
     * otro sistema, la librería JWT lanza una {@code JwtException}.
     * </p>
     *
     * @param token token JWT del que se desea obtener el correo electrónico
     * @return correo electrónico almacenado en el {@code subject} del token
     */
    public String extractEmail(String token) {

        return parser.parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
