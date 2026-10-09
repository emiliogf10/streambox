package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import jakarta.servlet.http.Cookie;

/**
 * Tests unitarios de {@link JwtAuthenticationFilter}: qué excepciones se
 * tratan como «token no válido» (petición anónima) y cuáles se dejan subir para
 * que el cliente reciba un 500.
 *
 * <p>
 * <b>Bug que protege.</b> El filtro capturaba {@code Exception} alrededor de
 * la validación del token <em>y</em> de la consulta del usuario. Con un token
 * válido y la base de datos caída, la {@code DataAccessException} se tragaba,
 * la petición seguía como anónima, la ruta protegida respondía 401 y el
 * frontend ({@code lib/api.ts}) cerraba la sesión. Sin el arreglo, los tests
 * {@code unFalloDeLaBaseDeDatos...} y {@code unaExcepcionInesperada...} fallan
 * (no se lanza nada y la cadena continúa).
 * </p>
 *
 * <p>
 * Además fija el log: la clase de la excepción a {@code DEBUG}, nunca su
 * mensaje (que puede reproducir contenido del token que controla el cliente).
 * </p>
 */
class JwtAuthenticationFilterTest {

    private static final String PROTECTED = "/api/users/me";
    private static final String TOKEN = "token-de-prueba";
    private static final String EMAIL = "ana@filtro.test";

    /** Mensaje que simula contenido del token reproducido por jjwt. */
    private static final String CLIENT_CONTROLLED = "MARCADOR-DEL-CLIENTE-7f3a";

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final JwtAuthenticationFilter filter = filter(jwtService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Fallos reales: deben subir (500), no convertirse en "anónimo" (401)
    // ------------------------------------------------------------------

    /** Con un token válido por cabecera, la caída de la BD sube tal cual. */
    @Test
    void unFalloDeLaBaseDeDatosConBearerSePropagaYNoSeTrataComoAnonimo() {
        DataAccessResourceFailureException dbDown = dbDown();
        when(jwtService.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenThrow(dbDown);
        MockFilterChain chain = new MockFilterChain();

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain));

        assertSame(dbDown, thrown);
        assertNull(chain.getRequest(), "la cadena no debe continuar como si fuera anónima");
        assertNull(currentAuthentication());
    }

    /** Lo mismo con el token en la cookie (el caso del navegador). */
    @Test
    void unFalloDeLaBaseDeDatosConCookieSePropagaYNoSeTrataComoAnonimo() {
        when(jwtService.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenThrow(dbDown());
        MockFilterChain chain = new MockFilterChain();

        assertThrows(DataAccessResourceFailureException.class,
                () -> filter.doFilter(cookieRequest("GET"), new MockHttpServletResponse(), chain));

        assertNull(chain.getRequest());
        assertNull(currentAuthentication());
    }

    /**
     * Con cookie y método no seguro, el usuario se consulta antes de la
     * comprobación CSRF: la caída de la BD también da 500, no un 403.
     */
    @Test
    void unFalloDeLaBaseDeDatosEnUnaPeticionNoSeguraPorCookieSePropaga() {
        when(jwtService.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenThrow(dbDown());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(DataAccessResourceFailureException.class,
                () -> filter.doFilter(cookieRequest("POST"), response, new MockFilterChain()));

        assertEquals(200, response.getStatus(), "no debe haberse escrito ningún 403");
    }

    /**
     * Cualquier excepción que no sea de jjwt (ni el
     * {@code IllegalArgumentException} del token vacío) es un fallo del
     * servidor y también sube: solo se perdona lo que significa «token no
     * válido».
     */
    @Test
    void unaExcepcionInesperadaAlValidarElTokenSePropaga() {
        when(jwtService.extractEmail(TOKEN)).thenThrow(new IllegalStateException("fallo interno"));
        MockFilterChain chain = new MockFilterChain();

        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain));

        assertNull(chain.getRequest());
        verify(userRepository, never()).findByEmail(anyString());
    }

    // ------------------------------------------------------------------
    // Token no válido o usuario inexistente: petición anónima (como antes)
    // ------------------------------------------------------------------

    /** Las excepciones que lanza jjwt (y la del token vacío) para un token no válido. */
    static Stream<Arguments> invalidTokenExceptions() {
        return Stream.of(
                Arguments.of(new MalformedJwtException(CLIENT_CONTROLLED)),
                Arguments.of(new SignatureException(CLIENT_CONTROLLED)),
                Arguments.of(new ExpiredJwtException(null, null, CLIENT_CONTROLLED)),
                Arguments.of(new UnsupportedJwtException(CLIENT_CONTROLLED)),
                Arguments.of(new WeakKeyException(CLIENT_CONTROLLED)),
                Arguments.of(new DecodingException(CLIENT_CONTROLLED)),
                Arguments.of(new JwtException(CLIENT_CONTROLLED)),
                Arguments.of(new IllegalArgumentException(CLIENT_CONTROLLED)));
    }

    /** Con cabecera: la petición sigue sin autenticar y ni se consulta la BD. */
    @ParameterizedTest
    @MethodSource("invalidTokenExceptions")
    void unTokenNoValidoPorCabeceraDejaLaPeticionAnonima(RuntimeException invalid) throws Exception {
        when(jwtService.extractEmail(TOKEN)).thenThrow(invalid);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "la cadena debe continuar");
        assertNull(currentAuthentication());
        verify(userRepository, never()).findByEmail(anyString());
    }

    /**
     * Con cookie y método no seguro sin cabecera CSRF: sigue siendo una
     * petición anónima (el 401 lo pone después Spring Security), no un 403
     * {@code CSRF_REJECTED} ni un 500.
     */
    @ParameterizedTest
    @MethodSource("invalidTokenExceptions")
    void unTokenNoValidoPorCookieDejaLaPeticionAnonima(RuntimeException invalid) throws Exception {
        when(jwtService.extractEmail(TOKEN)).thenThrow(invalid);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(cookieRequest("POST"), response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertNull(currentAuthentication());
    }

    /** Token válido de una cuenta borrada: anónima, no un error del servidor. */
    @Test
    void unTokenValidoDeUnUsuarioInexistenteDejaLaPeticionAnonima() throws Exception {
        when(jwtService.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertNull(currentAuthentication());
    }

    /** Un token firmado sin {@code subject} no identifica a nadie: ni se consulta la BD. */
    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void unTokenSinSubjectDejaLaPeticionAnonima(String subject) throws Exception {
        when(jwtService.extractEmail(TOKEN)).thenReturn(subject);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertNull(currentAuthentication());
        verify(userRepository, never()).findByEmail(anyString());
    }

    /** Control: con token válido y usuario existente, se autentica con su rol. */
    @Test
    void unTokenValidoDeUnUsuarioExistenteAutentica() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setEmail(EMAIL);
        user.setRole(Role.ADMIN);
        user.setCreatedAt(Instant.now());
        when(jwtService.extractEmail(TOKEN)).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        Authentication authentication = currentAuthentication();
        assertNotNull(authentication);
        assertEquals(new AuthenticatedUser(7L, EMAIL, Role.ADMIN), authentication.getPrincipal());
        assertEquals("ROLE_ADMIN", authentication.getAuthorities().iterator().next().getAuthority());
    }

    // ------------------------------------------------------------------
    // Log
    // ------------------------------------------------------------------

    /**
     * El log de un token no válido lleva la clase de la excepción, a
     * {@code DEBUG}, y nunca su mensaje ni la traza. Antes era
     * {@code WARN} con {@code e.getMessage()}.
     */
    @ParameterizedTest
    @MethodSource("invalidTokenExceptions")
    void elLogDeUnTokenNoValidoLlevaLaClaseADebugYNuncaElMensaje(RuntimeException invalid) throws Exception {
        when(jwtService.extractEmail(TOKEN)).thenThrow(invalid);

        List<ILoggingEvent> events = captureLogs(() ->
                filter.doFilter(bearerRequest(), new MockHttpServletResponse(), new MockFilterChain()));

        assertLoggedOnlyClassAtDebug(events, invalid.getClass().getSimpleName());
    }

    /**
     * Lo mismo con el {@link JwtService} real: el riesgo existe de verdad
     * (jjwt incluye en su mensaje el algoritmo que manda el cliente en la
     * cabecera del token) y el filtro no lo reproduce.
     */
    @Test
    void conJjwtRealElContenidoDelTokenNoLlegaAlLog() throws Exception {
        JwtService realService = new JwtService(
                new JwtProperties("secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres",
                        java.time.Duration.ofMinutes(15)));
        String header = base64Url("{\"alg\":\"" + CLIENT_CONTROLLED + "\"}");
        String hostileToken = header + "." + base64Url("{\"sub\":\"x\"}") + ".firma";

        // Premisa: el mensaje de jjwt sí contiene lo que puso el cliente.
        JwtException direct = assertThrows(JwtException.class, () -> realService.extractEmail(hostileToken));
        assertTrue(String.valueOf(direct.getMessage()).contains(CLIENT_CONTROLLED),
                "premisa del test: " + direct.getMessage());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED);
        request.addHeader("Authorization", "Bearer " + hostileToken);
        MockFilterChain chain = new MockFilterChain();

        List<ILoggingEvent> events = captureLogs(() ->
                filter(realService).doFilter(request, new MockHttpServletResponse(), chain));

        assertNotNull(chain.getRequest());
        assertLoggedOnlyClassAtDebug(events, direct.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private JwtAuthenticationFilter filter(JwtService service) {
        return new JwtAuthenticationFilter(service, userRepository, new SecurityErrorResponseWriter(),
                PathPatternRequestMatcher.withDefaults());
    }

    private static DataAccessResourceFailureException dbDown() {
        return new DataAccessResourceFailureException("Conexión rechazada (detalle interno de la BD)");
    }

    private static MockHttpServletRequest bearerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private static MockHttpServletRequest cookieRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, PROTECTED);
        request.setCookies(new Cookie(AuthCookieService.COOKIE_NAME, TOKEN));
        return request;
    }

    private static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Un único evento, a DEBUG, con la clase de la excepción, sin el mensaje ni la traza. */
    private static void assertLoggedOnlyClassAtDebug(List<ILoggingEvent> events, String exceptionClass) {
        assertEquals(1, events.size(), "eventos: " + events);
        ILoggingEvent event = events.get(0);
        assertEquals(Level.DEBUG, event.getLevel());
        assertTrue(event.getFormattedMessage().contains(exceptionClass), event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains(CLIENT_CONTROLLED), event.getFormattedMessage());
        assertNull(event.getThrowableProxy(), "no debe registrarse la traza (incluye el mensaje)");
    }

    /** Acción del filtro que puede lanzar excepciones comprobadas. */
    @FunctionalInterface
    private interface FilterAction {
        void run() throws Exception;
    }

    /**
     * Ejecuta la acción capturando lo que escribe el logger del filtro, con
     * el nivel forzado a {@code DEBUG} (y restaurado después) para ver también
     * lo que en producción no se escribe.
     */
    private static List<ILoggingEvent> captureLogs(FilterAction action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }
}
