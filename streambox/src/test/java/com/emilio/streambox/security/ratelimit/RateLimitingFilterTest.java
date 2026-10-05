package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.emilio.streambox.security.SecurityErrorResponseWriter;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounterTest.MutableClock;

/**
 * Tests unitarios de {@link RateLimitingFilter}: qué peticiones cuentan como
 * login o registro.
 *
 * <p>
 * Se llama al filtro directamente, sin la cadena de Spring Security delante.
 * Así se prueba el reconocimiento de rutas por sí mismo, incluidas variantes
 * que en la aplicación real ya rechaza antes el cortafuegos
 * ({@code ;jsessionid}): si algún día se relajara, el límite seguiría
 * aplicándose. El límite es de 1 petición por IP para que la segunda ya se
 * rechace.
 * </p>
 */
class RateLimitingFilterTest {

    private static final String IP = "192.0.2.10";

    private final RateLimitingFilter filter = new RateLimitingFilter(
            new RateLimitProperties(
                    new RateLimitProperties.Rule(1, Duration.ofMinutes(1)),
                    new RateLimitProperties.Rule(1, Duration.ofHours(1)),
                    new RateLimitProperties.Lockout(5, Duration.ofMinutes(15))),
            new MutableClock(),
            new SecurityErrorResponseWriter(),
            PathPatternRequestMatcher.withDefaults());

    /**
     * Regresión: el filtro comparaba {@code getRequestURI()} (sin decodificar)
     * con {@code "/api/auth/login"}, así que estas variantes, que Spring MVC
     * atiende como el login, no se contaban.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/%6cogin", "/api/auth/%6C%6F%67%69%6E", "/%61pi/auth/login",
            "/api/auth/login;jsessionid=abc", "/api;v=1/auth/login"
    })
    void lasVariantesDeLaRutaDelLoginCompartenElLimite(String rawPath) throws Exception {
        assertPassed(post("/api/auth/login"));

        assertRejected(post(rawPath));
    }

    /** Lo mismo al revés: la variante gasta el hueco y la ruta normal ya no pasa. */
    @Test
    void unaVarianteDelLoginGastaElMismoHuecoQueLaRutaNormal() throws Exception {
        assertPassed(post("/api/auth/%6cogin"));

        assertRejected(post("/api/auth/login"));
    }

    /** Regresión: {@code POST /api/%75sers} creaba cuentas sin pasar por el límite. */
    @ParameterizedTest
    @ValueSource(strings = { "/api/%75sers", "/api/user%73", "/%61pi/users", "/api/users;jsessionid=abc" })
    void lasVariantesDeLaRutaDelRegistroCompartenElLimite(String rawPath) throws Exception {
        assertPassed(post("/api/users"));

        assertRejected(post(rawPath));
    }

    /**
     * Peticiones que no se limitan porque no son el login ni el registro.
     * La barra final y las mayúsculas tampoco las enruta Spring MVC ni las
     * hace públicas la regla de autorización (lo comprueba
     * {@code RateLimitingIntegrationTest}): se limita exactamente lo público.
     */
    @ParameterizedTest
    @CsvSource({
            "GET, /api/auth/login",
            "GET, /api/users",
            "POST, /api/users/me/favorites/1",
            "POST, /api/auth/login/extra",
            "POST, /api/users/",
            "POST, /api/Users",
            "POST, /api/auth/Login"
    })
    void otrasPeticionesNoSeLimitan(String method, String rawPath) throws Exception {
        assertPassed(request(method, rawPath));
        assertPassed(request(method, rawPath));
    }

    /** El login y el registro tienen contadores distintos. */
    @Test
    void loginYRegistroNoCompartenContador() throws Exception {
        assertPassed(post("/api/auth/login"));

        assertPassed(post("/api/users"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static MockHttpServletRequest post(String rawPath) {
        return request("POST", rawPath);
    }

    /** Petición con la ruta tal cual (sin volver a codificarla), como llega al contenedor. */
    private static MockHttpServletRequest request(String method, String rawPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, rawPath);
        request.setRemoteAddr(IP);
        return request;
    }

    private void assertPassed(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "La petición debía continuar: " + request.getRequestURI());
        assertEquals(200, response.getStatus());
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "La petición no debía continuar: " + request.getRequestURI());
        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
    }
}
