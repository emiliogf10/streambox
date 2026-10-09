package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.emilio.streambox.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Tests unitarios de {@link JwtService} (no necesitan contexto de Spring). */
class JwtServiceTest {

    private static final String SECRET = "secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres";

    private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(15)));

    private static User user() {
        User user = new User();
        user.setEmail("ana@test.com");
        return user;
    }

    /**
     * Clave HMAC-SHA256 del secreto. Con {@code Keys.hmacShaKeyFor} el
     * algoritmo dependería de la longitud (con {@link #SECRET}, HS384) y el
     * servicio rechazaría esos tokens por el algoritmo, no por lo que prueba
     * cada test (caducidad, emisor...).
     */
    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
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
        assertEquals(900, lifetimeSeconds);
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

    // ------------------------------------------------------------------
    // Algoritmo fijo HS256 (tarea 29)
    // ------------------------------------------------------------------

    /** Secreto de 64 caracteres: con él, Keys.hmacShaKeyFor elegiría HS512. */
    private static final String LONG_SECRET = "s".repeat(64);

    /**
     * Con un secreto largo, el algoritmo sigue siendo HS256. Antes la clave
     * salía de {@code Keys.hmacShaKeyFor}, que con 64 bytes elige HS512: el
     * algoritmo dependía de la longitud del secreto.
     */
    @Test
    void firmaSiempreConHs256AunqueElSecretoSeaLargo() {
        JwtService service = new JwtService(new JwtProperties(LONG_SECRET, Duration.ofMinutes(15)));

        String token = service.generateToken(user());

        String header = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"HS256\""), header);
        assertEquals("ana@test.com", service.extractEmail(token));
    }

    /**
     * Un token bien firmado con el mismo secreto pero con HS384 o HS512 se
     * rechaza: el parser solo conoce HS256 y no deja que la cabecera del token
     * elija el algoritmo. Sin la restricción, jjwt los aceptaría (la clave es
     * lo bastante larga para los tres).
     */
    @ParameterizedTest
    @ValueSource(strings = { "HS384", "HS512" })
    void rechazaUnTokenFirmadoConOtroAlgoritmoHmacYElMismoSecreto(String algorithm) {
        JwtService service = new JwtService(new JwtProperties(LONG_SECRET, Duration.ofMinutes(15)));
        SecretKey key = Keys.hmacShaKeyFor(LONG_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject("ana@test.com")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, "HS384".equals(algorithm) ? Jwts.SIG.HS384 : Jwts.SIG.HS512)
                .compact();

        assertThrows(JwtException.class, () -> service.extractEmail(token));
    }

    /**
     * Emisión y comprobación usan el {@link Clock} de la aplicación: con el
     * reloj adelantado más allá de la vida del token, se rechaza como
     * caducado (aunque en tiempo real aún fuese válido). Es lo que permite
     * probar la caducidad del acceso sin esperar 15 minutos.
     */
    @Test
    void laCaducidadSeCompruebaConElRelojDeLaAplicacion() {
        Instant start = Instant.parse("2026-10-08T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(15)), clock);
        String token = service.generateToken(user());

        Claims claims = Jwts.parser().verifyWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256")).clock(() -> Date.from(start)).build().parseSignedClaims(token).getPayload();
        assertEquals(start, claims.getIssuedAt().toInstant());

        clock.now = start.plus(Duration.ofMinutes(14));
        assertEquals("ana@test.com", service.extractEmail(token));

        clock.now = start.plus(Duration.ofMinutes(16));
        assertThrows(ExpiredJwtException.class, () -> service.extractEmail(token));
    }

    /** Reloj que solo cambia cuando el test lo pide. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ------------------------------------------------------------------
    // Contrato con JwtAuthenticationFilter: un token no válido solo puede
    // producir JwtException o IllegalArgumentException
    // ------------------------------------------------------------------

    /**
     * Tokens hostiles que cualquiera puede enviar sin conocer el secreto: mal
     * formados, con Base64 o JSON inválidos, cabeceras raras, algoritmos no
     * admitidos, sin firma, cifrados (JWE), comprimidos...
     *
     * <p>
     * {@code JwtAuthenticationFilter} solo trata como «token no válido»
     * (petición anónima → 401) {@link JwtException} e
     * {@link IllegalArgumentException}; cualquier otra excepción se deja subir
     * y da un 500. Este test fija que jjwt no lanza otra cosa para estas
     * entradas: si una versión nueva de jjwt cambiara eso, un token basura
     * pasaría a dar 500 (y a escribir una traza a ERROR) en lugar de 401.
     * </p>
     */
    static Stream<String> hostileTokens() {
        String payload = b64("{\"iss\":\"streambox\",\"sub\":\"ana@test.com\"}");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            deep.append("{\"a\":");
        }
        return Stream.of(
                "", "   ", ".", "..", "...", "a.b", "a.b.c.d", "a.b.c.d.e", "a.b.c.d.e.f",
                "%%%.e30.firma", "e30.%%%.firma", "e30.e30.%%%",
                b64("no es json") + "." + payload + ".firma",
                b64("[]") + "." + payload + ".firma",
                b64("123") + "." + payload + ".firma",
                b64("null") + "." + payload + ".firma",
                b64("{}") + "." + payload + ".firma",
                b64("{\"alg\":123}") + "." + payload + ".firma",
                b64("{\"alg\":null}") + "." + payload + ".firma",
                b64("{\"alg\":[\"HS256\"]}") + "." + payload + ".firma",
                b64("{\"alg\":\"none\"}") + "." + payload + ".",
                b64("{\"alg\":\"HS256\"}") + "." + payload + ".",
                b64("{\"alg\":\"HS512\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"RS256\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"ES256\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"PS256\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"EdDSA\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"crit\":[\"exp\"]}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"crit\":\"b64\",\"b64\":false}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"b64\":false,\"crit\":[\"b64\"]}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"zip\":\"DEF\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"zip\":\"XYZ\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"jwk\":\"x\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"jwk\":{\"kty\":\"oct\",\"k\":\"AAAA\"}}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"x5c\":\"x\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"x5u\":\"::no es una url::\"}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"kid\":{}}") + "." + payload + ".firma",
                b64("{\"alg\":\"HS256\",\"typ\":5}") + "." + payload + ".firma",
                b64(deep.toString()) + "." + payload + ".firma",
                b64("{\"alg\":\"dir\",\"enc\":\"A256GCM\"}") + ".." + b64("iv") + "." + b64("x") + "." + b64("tag"),
                b64("{\"alg\":\"A128KW\",\"enc\":\"A128GCM\"}") + "." + b64("k") + "." + b64("iv") + "."
                        + b64("x") + "." + b64("tag"),
                b64("{\"alg\":\"dir\",\"enc\":\"XYZ\"}") + ".." + b64("iv") + "." + b64("x") + "." + b64("tag"),
                b64("{\"alg\":\"HS256\"}") + ".." + b64("iv") + "." + b64("x") + "." + b64("tag"));
    }

    @ParameterizedTest
    @MethodSource("hostileTokens")
    void unTokenHostilSoloLanzaJwtExceptionOIllegalArgumentException(String token) {
        Throwable thrown = assertThrows(Throwable.class, () -> jwtService.extractEmail(token),
                "un token hostil nunca puede aceptarse");

        assertInvalidTokenException(thrown);
    }

    /**
     * Con firma correcta (lo que solo puede hacer quien tiene el secreto) pero
     * claims con tipos inesperados: aceptarlos o rechazarlos depende de jjwt,
     * pero si los rechaza tiene que ser con las mismas excepciones.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "no es json", "[]", "123", "null", "{}",
            "{\"iss\":\"streambox\",\"exp\":\"mañana\"}",
            "{\"iss\":\"streambox\",\"exp\":{}}",
            "{\"iss\":\"streambox\",\"exp\":-1}",
            "{\"iss\":\"streambox\",\"nbf\":99999999999}",
            "{\"iss\":\"streambox\",\"iat\":\"x\"}",
            "{\"iss\":[\"streambox\"]}",
            "{\"iss\":123}",
            "{\"iss\":\"streambox\",\"sub\":123}",
            "{\"iss\":\"streambox\",\"sub\":{}}",
            "{\"iss\":\"streambox\",\"sub\":[\"a\",\"b\"]}",
            "{\"iss\":\"streambox\",\"aud\":5}",
            "{\"iss\":\"streambox\",\"jti\":{}}" })
    void unTokenFirmadoConClaimsRarosSoloLanzaJwtExceptionOIllegalArgumentException(String claims) {
        String token = signedWithRealKey(claims);

        try {
            jwtService.extractEmail(token);
        } catch (Throwable thrown) {
            assertInvalidTokenException(thrown);
        }
    }

    /**
     * Control del test anterior: la firma hecha a mano es correcta (si no, todos
     * esos casos fallarían ya en la firma y no probarían nada de los claims).
     */
    @Test
    void laFirmaHechaAManoEsValida() {
        String token = signedWithRealKey("{\"iss\":\"streambox\",\"sub\":\"ana@test.com\"}");

        assertEquals("ana@test.com", jwtService.extractEmail(token));
    }

    private static void assertInvalidTokenException(Throwable thrown) {
        assertTrue(thrown instanceof JwtException || thrown instanceof IllegalArgumentException,
                "jjwt lanzó " + thrown.getClass().getName() + ": el filtro lo trataría como un 500");
    }

    /**
     * Firma HMAC-SHA256 hecha a mano con la clave real (HS256 es el único
     * algoritmo que acepta el servicio).
     */
    private static String signedWithRealKey(String claimsJson) {
        try {
            SecretKey secretKey = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            String signingInput = b64("{\"alg\":\"HS256\"}") + "." + b64(claimsJson);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + "." + signature;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
