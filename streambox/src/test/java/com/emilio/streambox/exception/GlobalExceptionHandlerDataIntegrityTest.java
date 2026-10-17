package com.emilio.streambox.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Violaciones de integridad que ningún servicio tradujo
 * ({@link GlobalExceptionHandler#handleDataIntegrityViolation}).
 *
 * <p>
 * <b>El fallo que cubre.</b> Antes cualquier violación respondía un 409
 * «conflicto con datos existentes» y no escribía nada en el log, aunque los
 * servicios ({@code EpisodeService}, {@code SeriesFavoriteService}) dijeran que
 * lo inesperado «acaba en un 500 y en el log». Sin el arreglo, estos tests
 * reciben un 409 {@code DATA_INTEGRITY_VIOLATION} y ningún evento de log.
 * </p>
 *
 * <p>
 * Las excepciones imitan la forma real (Spring envuelve a Hibernate, que
 * envuelve al {@link SQLException} del driver) y llevan en su mensaje un email,
 * como el {@code Detail: Key (email)=(...)} de PostgreSQL: ni la respuesta ni
 * el log deben contenerlo.
 * </p>
 */
class GlobalExceptionHandlerDataIntegrityTest {

    private static final String PERSONAL_DATA = "ana.secreta@example.com";

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("POST", "/api/users");
        request.setQueryString("email=" + PERSONAL_DATA);

        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void unaViolacionNoTraducidaDa500InternalErrorConElMensajeGenerico() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(uniqueViolation(), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        ErrorResponse body = response.getBody();
        assertEquals(ErrorCode.INTERNAL_ERROR, body.getCode());
        assertEquals(GenericHttpError.INTERNAL_ERROR_MESSAGE, body.getMessage());
        assertEquals("/api/users", body.getPath());
        assertNull(body.getValidationErrors());
    }

    /**
     * Una sola línea {@code ERROR} con lo necesario para encontrar el fallo:
     * método, ruta, {@code SQLSTATE}, restricción y la línea de la aplicación
     * donde se originó (aquí, este test).
     */
    @Test
    void seRegistraEnErrorConElSqlstateLaRestriccionYElOrigen() {
        handler.handleDataIntegrityViolation(uniqueViolation(), request);

        ILoggingEvent event = singleEvent();
        assertEquals(Level.ERROR, event.getLevel());
        String message = event.getFormattedMessage();
        assertTrue(message.contains("POST /api/users"), message);
        assertTrue(message.contains("SQLSTATE=23505"), message);
        assertTrue(message.contains("restricción=uk_users_email"), message);
        assertTrue(message.contains("origen=" + GlobalExceptionHandlerDataIntegrityTest.class.getName()), message);
    }

    /**
     * Ni los valores de la fila (el email del {@code Detail} de PostgreSQL) ni
     * el SQL llegan al log: no se adjunta la excepción (su traza imprimiría los
     * mensajes de toda la cadena) ni la consulta de la URL.
     */
    @Test
    void elLogNoIncluyeLosValoresDeLaFilaNiElSqlNiLaTraza() {
        handler.handleDataIntegrityViolation(uniqueViolation(), request);

        ILoggingEvent event = singleEvent();
        assertFalse(event.getFormattedMessage().contains(PERSONAL_DATA), event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains("insert into"), event.getFormattedMessage());
        assertNull(event.getThrowableProxy(), "la traza llevaría los mensajes con los valores");
    }

    /**
     * El NUL en un texto (el caso real que llegaba aquí) es un error de datos
     * de PostgreSQL ({@code 22021}): no viola ninguna restricción con nombre.
     */
    @Test
    void unErrorDeDatosSinRestriccionSeRegistraConRestriccionDesconocida() {
        SQLException driver = new SQLException(
                "ERROR: invalid byte sequence for encoding \"UTF8\": 0x00", "22021");
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement", new DataException("could not execute statement", driver));

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(exception, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        String message = singleEvent().getFormattedMessage();
        assertTrue(message.contains("SQLSTATE=22021"), message);
        assertTrue(message.contains("restricción=desconocido"), message);
    }

    /**
     * De punta a punta por Spring MVC (sin contexto completo): una violación
     * que sale de un controlador la atiende este manejador, y no el genérico de
     * {@link Exception}, con un 500 en JSON.
     */
    @Test
    void desdeUnControladorLaRespuestaEsUn500EnJsonYQuedaEnElLog() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/prueba/integridad"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(GenericHttpError.INTERNAL_ERROR_MESSAGE));

        String message = singleEvent().getFormattedMessage();
        assertTrue(message.startsWith("Violación de integridad de datos no traducida"), message);
        assertTrue(message.contains("origen=" + FailingController.class.getName() + ".fail"), message);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Controlador mínimo que lanza una violación como lo haría un repositorio. */
    @RestController
    static class FailingController {

        @PostMapping("/prueba/integridad")
        void fail() {
            // Se crea aquí, no en uniqueViolation(), para que la primera línea
            // de la aplicación en su traza sea este método.
            ConstraintViolationException cause = hibernateUniqueViolation();
            throw new DataIntegrityViolationException(cause.getMessage(), cause);
        }
    }

    /** Violación de unicidad con la forma y los mensajes reales de PostgreSQL. */
    private static DataIntegrityViolationException uniqueViolation() {
        ConstraintViolationException hibernate = hibernateUniqueViolation();
        return new DataIntegrityViolationException(hibernate.getMessage(), hibernate);
    }

    private static ConstraintViolationException hibernateUniqueViolation() {
        String detail = "ERROR: duplicate key value violates unique constraint \"uk_users_email\"\n"
                + "  Detail: Key (email)=(" + PERSONAL_DATA + ") already exists.";
        SQLException driver = new SQLException(detail, "23505");
        return new ConstraintViolationException(
                "could not execute statement [" + detail + "] [insert into users (email) values (?)]",
                driver, "uk_users_email");
    }

    private ILoggingEvent singleEvent() {
        List<ILoggingEvent> events = List.copyOf(appender.list);
        assertEquals(1, events.size(), () -> "se esperaba una línea de log: " + events);
        return events.get(0);
    }
}
