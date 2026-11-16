package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.emilio.streambox.repository.UsernameReservationRepository;

/**
 * La limpieza de las reservas de nombre caducadas está programada de verdad
 * cuando {@code streambox.username-reservations.cleanup.enabled=true} (lo
 * normal fuera de los tests): una reserva caducada desaparece sin que nadie
 * llame al servicio, y una vigente se queda. Comprueba también que el
 * arranque acepta el intervalo con el formato de duración de Spring Boot.
 * Mismo planteamiento que {@code RefreshTokenCleanupSchedulingIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "streambox.username-reservations.cleanup.enabled=true",
        "streambox.username-reservations.cleanup.interval=200ms",
        "streambox.username-reservations.cleanup.initial-delay=0s"
})
class UsernameReservationCleanupSchedulingIntegrationTest {

    private static final Duration TTL = Duration.ofHours(24);

    @Autowired private ApplicationContext context;
    @Autowired private UsernameReservationRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM username_reservations");
    }

    @Test
    void laTareaProgramadaBorraLasCaducadasYDejaLasVigentes() throws Exception {
        assertEquals(1, context.getBeanNamesForType(UsernameReservationCleanupConfig.class).length);
        Instant now = Instant.now();
        repository.reserve("limpieza_caducada", "caducada@cleanup.reservas.test", now.minus(Duration.ofHours(48)), TTL);
        repository.reserve("limpieza_vigente", "vigente@cleanup.reservas.test", now, TTL);

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (exists("limpieza_caducada") && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertFalse(exists("limpieza_caducada"), "la limpieza programada no borró la reserva caducada");
        assertTrue(exists("limpieza_vigente"), "la limpieza programada borró una reserva vigente");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM username_reservation_owners WHERE username = 'limpieza_caducada'",
                Integer.class), "los dueños de la caducada deben irse en cascada");
    }

    private boolean exists(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM username_reservations WHERE username = ?",
                Integer.class, username) > 0;
    }
}
