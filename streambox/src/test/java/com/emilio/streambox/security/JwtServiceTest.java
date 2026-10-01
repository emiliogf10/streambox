package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Tests unitarios de {@link JwtService} (no necesitan contexto de Spring). */
class JwtServiceTest {

    private static final String SECRET = "secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres";

    private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, 1));

    private static User user() {
        User user = new User();
        user.setEmail("ana@test.com");
        return user;
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void elTokenGeneradoSePuedeLeerDeNuevo() {
        String token = jwtService.generateToken(user());

        assertEquals("ana@test.com", jwtService.extractEmail(token));
    }

    @Test
    void elTokenIncluyeEmisorYCaducaSegunLaConfiguracion() {
        String token = jwtService.generateToken(user());

        Claims claims = Jwts.parser().verifyWith(key(SECRET)).build()
                .parseSignedClaims(token).getPayload();

        assertEquals(JwtService.ISSUER, claims.getIssuer());
        long lifetimeSeconds =
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertEquals(3600, lifetimeSeconds);
    }

    @Test
    void rechazaUnTokenFirmadoConOtraClave() {
        String forged = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("ana@test.com")
                .signWith(key("otra-clave-distinta-de-al-menos-32-caracteres!!"))
                .compact();

        assertThrows(JwtException.class, () -> jwtService.extractEmail(forged));
    }

    @Test
    void rechazaUnTokenCaducado() {
        long now = System.currentTimeMillis();
        String expired = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("ana@test.com")
                .issuedAt(new Date(now - 120_000))
                .expiration(new Date(now - 60_000))
                .signWith(key(SECRET))
                .compact();

        assertThrows(ExpiredJwtException.class, () -> jwtService.extractEmail(expired));
    }

    @Test
    void rechazaUnTokenDeOtroEmisor() {
        String foreign = Jwts.builder()
                .issuer("otro-sistema")
                .subject("ana@test.com")
                .signWith(key(SECRET))
                .compact();

        assertThrows(JwtException.class, () -> jwtService.extractEmail(foreign));
    }

    @Test
    void rechazaTextoQueNoEsUnJwt() {
        assertThrows(JwtException.class, () -> jwtService.extractEmail("basura"));
    }
}
