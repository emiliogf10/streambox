package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.GlobalExceptionHandler;
import com.emilio.streambox.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Revisión de QA: que una violación de integridad <b>no deje datos personales
 * en el log</b>, con los mensajes reales de PostgreSQL.
 *
 * <p>
 * <b>Por qué contra PostgreSQL.</b> Su error de unicidad trae el valor de la
 * fila en el detalle ({@code Detail: Key (email)=(ana@...) already exists.}),
 * y H2 escribe otro texto. {@code GlobalExceptionHandlerDataIntegrityTest}
 * prueba el registro del manejador con excepciones fabricadas; aquí se usa la
 * excepción que lanza el driver de verdad.
 * </p>
 *
 * <p>
 * Dos alcances distintos:
 * </p>
 * <ul>
 * <li>la línea de {@link GlobalExceptionHandler} (lo que la tanda prometió):
 * {@code SQLSTATE}, restricción y origen, sin el email;</li>
 * <li>el log <b>entero</b> durante una carrera de registro real (lo que de
 * verdad acaba en el archivo): ahí escribe también Hibernate
 * (categoría {@code org.hibernate.orm.jdbc.error}), que no pasa por el manejador.</li>
 * </ul>
 */
class PostgresIntegrityViolationLogPrivacyQaIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String PERSONAL_EMAIL = "victima.privada@test.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private GlobalExceptionHandler handler;
    @MockitoSpyBean private UserRepository spiedUsers;

    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
        cleanDatabase();
    }

    /**
     * Con la excepción real de PostgreSQL (unicidad del email, cuyo mensaje
     * lleva el email), la línea del manejador da el {@code SQLSTATE} y la
     * restricción, y no el email.
     */
    @Test
    void laLineaDelManejadorConUnaViolacionRealDePostgresNoTraeElEmail() {
        saveUserWithEmail("primera", PERSONAL_EMAIL);
        User duplicate = newUser("segunda", PERSONAL_EMAIL);
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(duplicate));
        // Premisa: el mensaje del driver SÍ lleva el dato personal (si no, el test no probaría nada).
        assertTrue(fullText(error).contains(PERSONAL_EMAIL), "PostgreSQL debería incluir el valor en el detalle");
        appender.list.clear();

        var response = handler.handleDataIntegrityViolation(error, new MockHttpServletRequest("POST", "/api/users"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        List<ILoggingEvent> lines = appender.list.stream()
                .filter(e -> e.getLoggerName().equals(GlobalExceptionHandler.class.getName()))
                .toList();
        assertEquals(1, lines.size());
        String line = lines.get(0).getFormattedMessage();
        assertTrue(line.contains("SQLSTATE=23505"), line);
        assertTrue(line.contains("restricción=uk_users_email"), line);
        assertFalse(line.contains(PERSONAL_EMAIL), line);
        assertEquals(null, lines.get(0).getThrowableProxy(), "la traza (con los mensajes) no debe ir al log");
    }

    /**
     * Carrera de registro real (dos altas con el mismo email; la comprobación
     * previa no ve la otra): la API responde el 409 correcto, pero <b>ningún</b>
     * log debería contener el email de la víctima.
     *
     * <p>
     * <b>Fallaba</b> (incidencia de QA del 2026-10-17). Hibernate registra por
     * su cuenta cada error de JDBC (categoría
     * {@code org.hibernate.orm.jdbc.error}, que escribe
     * {@code SqlExceptionHelper}): una línea {@code WARN} con el mensaje de
     * PostgreSQL, {@code Detail: Key (email)=(victima.privada@test.com) already
     * exists.}, antes de que el servicio traduzca la
     * excepción, así que el cuidado del manejador no basta. Afectaba a todas las
     * violaciones de unicidad (registro, cambio de nombre, géneros...), también
     * a las traducidas. Corregido con
     * {@code logging.level.org.hibernate.orm.jdbc.error=OFF} en
     * {@code application.properties} (el manejador ya registra el
     * {@code SQLSTATE} y la restricción de las no traducidas).
     * </p>
     */
    @Test
    void unaCarreraDeRegistroNoDejaElEmailEnNingunLog() throws Exception {
        saveUserWithEmail("primera", PERSONAL_EMAIL);
        doReturn(false).when(spiedUsers).existsByEmail(PERSONAL_EMAIL);
        appender.list.clear();

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"username\":\"segunda\",\"email\":\"" + PERSONAL_EMAIL + "\","
                                + "\"password\":\"tres gatos pardos en el tejado\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));

        List<String> leaks = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + throwableText(event.getThrowableProxy());
            if (text.contains(PERSONAL_EMAIL)) {
                leaks.add(event.getLevel() + " " + event.getLoggerName() + ": " + event.getFormattedMessage());
            }
        }
        assertEquals(List.of(), leaks);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private void saveUserWithEmail(String username, String email) {
        userRepository.saveAndFlush(newUser(username, email));
    }

    private User newUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        return user;
    }

    private static String fullText(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null && t.getCause() != t; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    private static String throwableText(IThrowableProxy proxy) {
        StringBuilder text = new StringBuilder();
        for (IThrowableProxy p = proxy; p != null; p = p.getCause()) {
            text.append('\n').append(p.getMessage());
        }
        return text.toString();
    }
}
