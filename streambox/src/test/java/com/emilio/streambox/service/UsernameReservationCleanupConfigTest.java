package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Pruebas unitarias de la limpieza de reservas de nombre
 * ({@link UsernameReservationCleanupConfig}) y de la validación de sus
 * propiedades. Que la tarea se programa de verdad lo prueba
 * {@code UsernameReservationCleanupSchedulingIntegrationTest}.
 */
class UsernameReservationCleanupConfigTest {

    private final UsernameReservations reservations = mock(UsernameReservations.class);

    private final UsernameReservationCleanupConfig config = new UsernameReservationCleanupConfig(reservations,
            new UsernameReservationCleanupProperties(true, Duration.ofHours(1), Duration.ofMinutes(1)));

    @Test
    void cadaPasadaBorraLasReservasCaducadas() {
        when(reservations.deleteExpired()).thenReturn(3);

        config.cleanUp();

        verify(reservations).deleteExpired();
    }

    /**
     * Con la base de datos caída, la pasada no lanza nada (la tarea sigue
     * programada para la siguiente) y deja un aviso en el log.
     */
    @Test
    void unFalloDeLaLimpiezaSoloDejaUnAvisoEnElLog() {
        when(reservations.deleteExpired()).thenThrow(new DataAccessResourceFailureException("base de datos caída"));
        Logger logger = (Logger) LoggerFactory.getLogger(UsernameReservationCleanupConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(config::cleanUp);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, appender.list.size());
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("reservas de nombre"));
    }

    @Test
    void lasPropiedadesRechazanUnIntervaloNoPositivoOUnaEsperaNegativa() {
        assertThrows(IllegalArgumentException.class,
                () -> new UsernameReservationCleanupProperties(true, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new UsernameReservationCleanupProperties(true, null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new UsernameReservationCleanupProperties(true, Duration.ofHours(1), Duration.ofSeconds(-1)));
        assertDoesNotThrow(() -> new UsernameReservationCleanupProperties(true, Duration.ofMillis(200), Duration.ZERO));
    }
}
