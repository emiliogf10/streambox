package com.emilio.streambox.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.User;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Servicio encargado de la generación y validación de tokens JWT
 * utilizados para autenticar a los usuarios de Streambox.
 *
 * <p>
 * Los tokens generados por este servicio contienen el correo electrónico
 * del usuario como {@code subject} y están firmados mediante una clave
 * secreta utilizando un algoritmo HMAC.
 * </p>
 *
 * <p>
 * La clave secreta utilizada para las operaciones criptográficas se
 * obtiene desde la configuración de la aplicación y no se almacena
 * directamente en el código fuente.
 * </p>
 */
@Service
public class JwtService {

    /**
     * Clave secreta utilizada para firmar y verificar los tokens JWT.
     *
     * <p>
     * El valor se obtiene de la configuración de Spring mediante la
     * propiedad {@code jwt.secret}. La clave debe mantenerse fuera
     * del código fuente para evitar exponer información sensible.
     * </p>
     */
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Genera un token JWT para un usuario autenticado.
     *
     * <p>
     * El correo electrónico del usuario se almacena como
     * {@code subject} del token. El token incluye también la fecha
     * de emisión y una fecha de expiración una hora posterior.
     * </p>
     *
     * <p>
     * El token se firma utilizando la clave secreta obtenida mediante
     * {@link #getSigningKey()}, garantizando que pueda verificarse
     * posteriormente que el token no ha sido alterado.
     * </p>
     *
     * @param user usuario autenticado para el que se generará el token
     * @return token JWT firmado
     */
    public String generateToken(User user) {

        return Jwts.builder()
                .subject(user.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(
                        System.currentTimeMillis() + 1000 * 60 * 60))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Extrae el correo electrónico almacenado como {@code subject}
     * dentro de un token JWT.
     *
     * <p>
     * Durante el proceso se verifica la firma del token utilizando
     * la clave secreta de la aplicación. Si el token ha sido manipulado,
     * ha expirado, no es válido o no puede verificarse su firma,
     * la librería JWT producirá una excepción.
     * </p>
     *
     * <p>
     * El método solo devuelve el correo electrónico después de que
     * el token haya superado el proceso de análisis y verificación.
     * </p>
     *
     * @param token token JWT del que se desea obtener el correo electrónico
     * @return correo electrónico almacenado en el {@code subject} del token
     */
    public String extractEmail(String token) {

        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Obtiene la clave utilizada para firmar y verificar los tokens JWT.
     *
     * <p>
     * La clave se construye a partir del secreto configurado en
     * {@code jwt.secret}, convirtiendo el texto a bytes mediante
     * {@link StandardCharsets#UTF_8} y creando una clave HMAC mediante
     * {@link Keys#hmacShaKeyFor(byte[])}.
     * </p>
     *
     * <p>
     * El secreto configurado debe tener una longitud suficiente para
     * cumplir los requisitos de seguridad del algoritmo HMAC utilizado
     * por JJWT.
     * </p>
     *
     * @return clave secreta utilizada para las operaciones criptográficas
     */
    private SecretKey getSigningKey() {

        return Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));
    }
}