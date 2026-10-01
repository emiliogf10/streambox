package com.emilio.streambox.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.User;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Servicio encargado de la generación y validación de tokens JWT
 * utilizados para autenticar a los usuarios de Streambox.
 *
 * <p>
 * Los tokens contienen el correo electrónico del usuario como
 * {@code subject}, identifican a Streambox como emisor ({@code iss}) y están
 * firmados con una clave HMAC derivada de {@code jwt.secret}.
 * </p>
 *
 * <p>
 * La configuración llega ya validada mediante {@link JwtProperties}, y la
 * clave de firma se construye una sola vez al crear el servicio en lugar de
 * recalcularse en cada petición.
 * </p>
 */
@Service
public class JwtService {

    /** Valor del claim {@code iss} de los tokens emitidos y aceptados. */
    public static final String ISSUER = "streambox";

    private final SecretKey signingKey;

    private final Duration expiration;

    /**
     * Crea el servicio a partir de la configuración validada.
     *
     * @param properties propiedades {@code jwt.*} de la aplicación
     */
    public JwtService(JwtProperties properties) {

        this.signingKey = Keys.hmacShaKeyFor(
                properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofHours(properties.expirationHours());
    }

    /**
     * Genera un token JWT para un usuario autenticado.
     *
     * @param user usuario autenticado para el que se generará el token
     * @return token JWT firmado
     */
    public String generateToken(User user) {

        Date now = new Date();

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.getEmail())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration.toMillis()))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Extrae el correo electrónico almacenado como {@code subject}
     * dentro de un token JWT.
     *
     * <p>
     * Se verifica la firma, la fecha de expiración y que el emisor sea
     * Streambox. Si el token está manipulado, ha expirado o fue emitido por
     * otro sistema, la librería JWT lanza una excepción.
     * </p>
     *
     * @param token token JWT del que se desea obtener el correo electrónico
     * @return correo electrónico almacenado en el {@code subject} del token
     */
    public String extractEmail(String token) {

        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
