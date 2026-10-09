package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import com.emilio.streambox.security.refresh.IssuedRefreshToken;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests unitarios de {@link AuthCookieService}: atributos de las dos cookies
 * y el aviso de arranque cuando el perfil {@code prod} usa cookies sin
 * {@code Secure}.
 */
class AuthCookieServiceTest {

    private static final JwtProperties JWT =
            new JwtProperties("secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres", Duration.ofMinutes(15));

    @Test
    void laCookieDelRefreshSoloViajaAAuthYDuraLoQueLeQuedaAlToken() {
        AuthCookieService service = new AuthCookieService(new AuthCookieProperties(true), JWT, env());

        String cookie = service.refreshCookie(new IssuedRefreshToken("valor", Duration.ofDays(6)));

        assertTrue(cookie.startsWith("streambox_refresh=valor;"), cookie);
        assertTrue(cookie.contains("Path=/api/auth;"), cookie);
        assertTrue(cookie.contains("Max-Age=" + Duration.ofDays(6).toSeconds() + ";"), cookie);
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Strict"),
                cookie);
    }

    @Test
    void laCookieDelAccesoDuraLoQueElJwt() {
        AuthCookieService service = new AuthCookieService(new AuthCookieProperties(true), JWT, env());

        String cookie = service.sessionCookie("jwt");

        assertTrue(cookie.startsWith("streambox_token=jwt;"), cookie);
        assertTrue(cookie.contains("Path=/api;"), cookie);
        assertTrue(cookie.contains("Max-Age=900;"), cookie);
    }

    /**
     * {@code Secure=false} con el perfil {@code prod}: no impide arrancar (el
     * compose local va por HTTP), pero deja un WARN claro en el log.
     */
    @Test
    void avisaSiProdUsaCookiesSinSecure() {
        List<ILoggingEvent> events = capture(() ->
                new AuthCookieService(new AuthCookieProperties(false), JWT, env("prod")));

        assertEquals(1, events.size(), events.toString());
        assertEquals(Level.WARN, events.get(0).getLevel());
        String message = events.get(0).getFormattedMessage();
        assertTrue(message.contains("streambox.auth.cookie.secure=false"), message);
        assertTrue(message.contains("STREAMBOX_AUTH_COOKIE_SECURE=true"), message);
    }

    /** Sin aviso con {@code Secure} activado, o fuera de {@code prod} (dev, test, e2e). */
    @ParameterizedTest
    @CsvSource({ "true, prod", "false, dev", "false, e2e", "true, dev" })
    void noAvisaEnElRestoDeCasos(boolean secure, String profile) {
        List<ILoggingEvent> events = capture(() ->
                new AuthCookieService(new AuthCookieProperties(secure), JWT, env(profile)));

        assertTrue(events.isEmpty(), events.toString());
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static List<ILoggingEvent> capture(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthCookieService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
