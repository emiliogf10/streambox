package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Casos de prueba de las <strong>reservas de nombre de usuario</strong>
 * ({@code V13}, {@link UsernameReservationRepository}), escritos una sola vez
 * y ejecutados en los dos motores: H2
 * ({@link UsernameReservationRepositoryIntegrationTest}) y PostgreSQL real
 * ({@code PostgresUsernameReservationIntegrationTest}, que añade la
 * concurrencia). Mismo patrón que {@link LikesQueryContract}.
 *
 * <p>
 * Qué se prueba (la semántica que documenta {@code UsernameReservations}):
 * reservar un nombre libre, que repetirla no alarga la caducidad, que un
 * segundo email se suma como dueño con la <b>misma</b> caducidad, que una
 * reserva caducada no cuenta y se sustituye por una nueva (sin sus dueños
 * antiguos), la excepción del dueño en {@code isReservedForOthers}, la purga,
 * que la reserva va en la transacción del llamador y el formato del hash.
 * </p>
 *
 * <p>
 * Los instantes son segundos exactos: las dos bases guardan microsegundos y
 * así ninguna comparación depende del redondeo.
 * </p>
 */
public interface UsernameReservationQueryContract {

    /** Instante base de las reservas. */
    Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    /** Vida de una reserva ({@code streambox.email.verification-ttl}). */
    Duration TTL = Duration.ofHours(24);

    /** @return repositorio bajo prueba */
    UsernameReservationRepository reservations();

    /** @return acceso JDBC directo, para leer las filas */
    JdbcTemplate jdbc();

    /** @return plantilla de transacciones del contexto */
    TransactionTemplate tx();

    // ------------------------------------------------------------------
    // Reservar
    // ------------------------------------------------------------------

    /**
     * Un nombre libre queda reservado desde {@code now} hasta
     * {@code now + ttl} con ese email como único dueño (y el email guardado
     * solo como hash).
     */
    @Test
    default void reservarUnNombreLibreCreaLaReservaConSuCaducidadYSuDueno() {
        assertFalse(reservations().isReserved("libre", T0));

        assertTrue(reservations().reserve("libre", "ana@test.com", T0, TTL));

        assertTrue(reservations().isReserved("libre", T0));
        assertEquals(Map.of("created", T0, "expires", T0.plus(TTL)), reservationTimes("libre"));
        assertEquals(List.of(UsernameReservationRepository.emailHash("ana@test.com")), ownerHashes("libre"));
    }

    /**
     * Repetir la reserva con el mismo email no crea otra ni la alarga: sigue
     * caducando a las 24 h de la primera.
     */
    @Test
    default void repetirLaReservaNoLaAlarga() {
        reservations().reserve("repetido", "ana@test.com", T0, TTL);

        assertFalse(reservations().reserve("repetido", "ana@test.com", T0.plus(Duration.ofHours(23)), TTL));

        assertEquals(Map.of("created", T0, "expires", T0.plus(TTL)), reservationTimes("repetido"));
        assertEquals(1, ownerHashes("repetido").size());
        assertTrue(reservations().isReserved("repetido", T0.plus(TTL).minusSeconds(1)));
        assertFalse(reservations().isReserved("repetido", T0.plus(TTL)), "caduca a las 24 h de la primera");
    }

    /**
     * Un segundo email con el mismo nombre se suma como dueño sin alargar
     * nada: los dos caducan a la vez. Un tercero no es dueño.
     */
    @Test
    default void unSegundoEmailSeSumaComoDuenoConLaMismaCaducidad() {
        reservations().reserve("compartido", "ana@test.com", T0, TTL);

        assertFalse(reservations().reserve("compartido", "bea@test.com", T0.plus(Duration.ofHours(12)), TTL));

        assertEquals(Map.of("created", T0, "expires", T0.plus(TTL)), reservationTimes("compartido"));
        assertEquals(2, ownerHashes("compartido").size());
        Instant beforeExpiry = T0.plus(TTL).minusSeconds(1);
        assertFalse(reservations().isReservedForOthers("compartido", "ana@test.com", beforeExpiry));
        assertFalse(reservations().isReservedForOthers("compartido", "bea@test.com", beforeExpiry));
        assertTrue(reservations().isReservedForOthers("compartido", "eva@test.com", beforeExpiry));
        assertFalse(reservations().isReserved("compartido", T0.plus(TTL)), "los dos dueños caducan a la vez");
    }

    // ------------------------------------------------------------------
    // Caducidad
    // ------------------------------------------------------------------

    /**
     * A partir de {@code expires_at} (incluido) la reserva no cuenta para
     * nada, aunque siga en la tabla: ni el nombre está reservado ni lo está
     * «para otros».
     */
    @Test
    default void unaReservaCaducadaNoCuentaAunqueSigaEnLaTabla() {
        reservations().reserve("caduca", "ana@test.com", T0, TTL);

        Instant expiry = T0.plus(TTL);
        assertTrue(reservations().isReservedForOthers("caduca", "eva@test.com", expiry.minusSeconds(1)));
        assertFalse(reservations().isReserved("caduca", expiry));
        assertFalse(reservations().isReservedForOthers("caduca", "eva@test.com", expiry));
        assertFalse(reservations().isReserved("caduca", expiry.plus(Duration.ofDays(30))));
        assertEquals(1, count("username_reservations"), "sigue en la tabla hasta la purga");
    }

    /**
     * Reservar un nombre cuya reserva caducó crea una <b>nueva</b> (con su
     * propia caducidad) y la antigua desaparece con sus dueños: el email que la
     * hizo ya no es dueño de la nueva.
     */
    @Test
    default void reservarUnNombreCaducadoCreaUnaReservaNuevaSinLosDuenosAntiguos() {
        reservations().reserve("renovado", "ana@test.com", T0, TTL);
        Instant later = T0.plus(TTL).plus(Duration.ofHours(1));

        assertTrue(reservations().reserve("renovado", "bea@test.com", later, TTL));

        assertEquals(Map.of("created", later, "expires", later.plus(TTL)), reservationTimes("renovado"));
        assertEquals(List.of(UsernameReservationRepository.emailHash("bea@test.com")), ownerHashes("renovado"));
        assertTrue(reservations().isReservedForOthers("renovado", "ana@test.com", later));
        assertFalse(reservations().isReservedForOthers("renovado", "bea@test.com", later));
    }

    /**
     * Si el nombre caducó, el mismo email que lo reservó vuelve a reservarlo
     * desde cero (no se queda con la fila caducada por la clave primaria).
     */
    @Test
    default void elMismoEmailVuelveAReservarUnNombreCaducado() {
        reservations().reserve("vuelve", "ana@test.com", T0, TTL);
        Instant later = T0.plus(TTL);

        assertTrue(reservations().reserve("vuelve", "ana@test.com", later, TTL));

        assertTrue(reservations().isReserved("vuelve", later));
        assertEquals(Map.of("created", later, "expires", later.plus(TTL)), reservationTimes("vuelve"));
    }

    // ------------------------------------------------------------------
    // Excepción del dueño e independencia entre nombres
    // ------------------------------------------------------------------

    /**
     * Sin reserva no hay nada que esté reservado para otros; el dueño puede
     * usar su nombre; y la reserva de un nombre no afecta a otro (ni a uno
     * que solo difiere en mayúsculas: el llamador normaliza).
     */
    @Test
    default void isReservedForOthersSoloCuentaLasReservasVigentesDeEseNombre() {
        assertFalse(reservations().isReservedForOthers("nadie", "ana@test.com", T0));

        reservations().reserve("propio", "ana@test.com", T0, TTL);

        assertFalse(reservations().isReservedForOthers("propio", "ana@test.com", T0));
        assertTrue(reservations().isReservedForOthers("propio", "bea@test.com", T0));
        assertFalse(reservations().isReserved("otro", T0));
        assertFalse(reservations().isReserved("Propio", T0), "la comparación es exacta: el llamador normaliza");
        assertFalse(reservations().isReservedForOthers("otro", "bea@test.com", T0));
    }

    // ------------------------------------------------------------------
    // Cerrojo del cambio de nombre (lock / deleteLock)
    // ------------------------------------------------------------------

    /**
     * Sin reserva vigente, {@code lock} crea la fila del cerrojo (sin dueños) y
     * {@code deleteLock} la quita antes de confirmar: el cambio de nombre no
     * deja nada.
     */
    @Test
    default void lockSinReservaCreaUnCerrojoQueDeleteLockQuita() {
        Boolean created = tx().execute(status -> {
            boolean result = reservations().lock("cerrojo", T0, TTL);
            assertEquals(1, count("username_reservations"), "dentro de la transacción, la fila existe");
            reservations().deleteLock("cerrojo");
            return result;
        });

        assertEquals(Boolean.TRUE, created);
        assertEquals(0, count("username_reservations"));
        assertEquals(0, count("username_reservation_owners"));
    }

    /**
     * Con una reserva vigente, {@code lock} la bloquea sin tocarla y
     * {@code deleteLock} no la borra (tiene dueños): nunca se lleva una reserva
     * de verdad.
     */
    @Test
    default void lockSobreUnaReservaVigenteNoLaCambiaYDeleteLockNoLaBorra() {
        reservations().reserve("ocupado", "ana@test.com", T0, TTL);

        Boolean created = tx().execute(status -> {
            boolean result = reservations().lock("ocupado", T0.plus(Duration.ofHours(1)), TTL);
            reservations().deleteLock("ocupado");
            return result;
        });

        assertEquals(Boolean.FALSE, created);
        assertEquals(Map.of("created", T0, "expires", T0.plus(TTL)), reservationTimes("ocupado"));
        assertEquals(List.of(UsernameReservationRepository.emailHash("ana@test.com")), ownerHashes("ocupado"));
    }

    /**
     * Una reserva caducada no cuenta: {@code lock} la sustituye por su cerrojo
     * (devuelve {@code true}) y, al quitarlo, no queda nada (la caducada ya no
     * servía).
     */
    @Test
    default void lockSobreUnaReservaCaducadaCuentaComoLibre() {
        reservations().reserve("pasada", "ana@test.com", T0, TTL);

        Boolean created = tx().execute(status -> {
            boolean result = reservations().lock("pasada", T0.plus(TTL), TTL);
            reservations().deleteLock("pasada");
            return result;
        });

        assertEquals(Boolean.TRUE, created);
        assertEquals(0, count("username_reservations"));
        assertEquals(0, count("username_reservation_owners"));
    }

    /**
     * Un cerrojo fuera de una transacción no serviría (se soltaría al acabar
     * la sentencia): {@code lock} y {@code deleteLock} la exigen.
     */
    @Test
    default void lockYDeleteLockExigenUnaTransaccion() {
        assertThrows(IllegalTransactionStateException.class, () -> reservations().lock("suelto", T0, TTL));
        assertThrows(IllegalTransactionStateException.class, () -> reservations().deleteLock("suelto"));

        assertEquals(0, count("username_reservations"));
    }

    // ------------------------------------------------------------------
    // Purga
    // ------------------------------------------------------------------

    /**
     * La purga borra las reservas con {@code expires_at <= now} (y sus dueños,
     * por la cascada), devuelve cuántos nombres borró y no toca las vigentes.
     */
    @Test
    default void deleteExpiredBorraSoloLasCaducadasYSusDuenos() {
        reservations().reserve("vieja", "ana@test.com", T0, TTL);
        reservations().reserve("vieja", "bea@test.com", T0, TTL);
        reservations().reserve("nueva", "ana@test.com", T0.plus(Duration.ofHours(12)), TTL);

        assertEquals(0, reservations().deleteExpired(T0.plus(TTL).minusSeconds(1)));
        assertEquals(1, reservations().deleteExpired(T0.plus(TTL)), "el límite cuenta como caducado");

        assertEquals(List.of("nueva"), jdbc().queryForList(
                "SELECT username FROM username_reservations", String.class));
        assertEquals(List.of("nueva"), jdbc().queryForList(
                "SELECT username FROM username_reservation_owners", String.class));
        assertTrue(reservations().isReserved("nueva", T0.plus(TTL)));
        assertEquals(0, reservations().deleteExpired(T0.plus(TTL)), "una segunda purga no borra nada");
    }

    // ------------------------------------------------------------------
    // Transacciones y validación
    // ------------------------------------------------------------------

    /**
     * La reserva se une a la transacción del llamador: si el registro se
     * deshace, no queda nada (ni la reserva ni el dueño).
     */
    @Test
    default void laReservaSeDeshaceConLaTransaccionDelLlamador() {
        tx().executeWithoutResult(status -> {
            reservations().reserve("deshecha", "ana@test.com", T0, TTL);
            assertTrue(reservations().isReserved("deshecha", T0), "dentro de la transacción se ve");
            status.setRollbackOnly();
        });

        assertFalse(reservations().isReserved("deshecha", T0));
        assertEquals(0, count("username_reservations"));
        assertEquals(0, count("username_reservation_owners"));
    }

    /**
     * Una vida nula, cero o negativa es un error del llamador y no escribe
     * nada. Llega como {@link InvalidDataAccessApiUsageException}: el
     * {@code @Repository} traduce así las {@link IllegalArgumentException}
     * (la original queda como causa).
     */
    @Test
    default void unaVidaNoPositivaSeRechazaSinEscribirNada() {
        for (Duration ttl : new Duration[] { Duration.ZERO, Duration.ofSeconds(-1), null }) {
            InvalidDataAccessApiUsageException error = assertThrows(InvalidDataAccessApiUsageException.class,
                    () -> reservations().reserve("x", "a@test.com", T0, ttl), String.valueOf(ttl));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
        assertThrows(NullPointerException.class, () -> reservations().reserve("x", null, T0, TTL));

        assertEquals(0, count("username_reservations"));
    }

    /**
     * El hash es el SHA-256 en hexadecimal en minúsculas (vector conocido de
     * {@code "abc"}), del texto en UTF-8 (dos emails que solo difieren en un
     * carácter no ASCII no chocan, como pasaría codificando en ASCII) y es lo
     * único que se guarda: el email en claro no está en la tabla.
     */
    @Test
    default void elEmailSeGuardaComoSha256HexadecimalDelTextoEnUtf8() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UsernameReservationRepository.emailHash("abc"));
        assertNotEquals(UsernameReservationRepository.emailHash("ñandu@test.com"),
                UsernameReservationRepository.emailHash("?andu@test.com"));

        reservations().reserve("hash", "ñandu@test.com", T0, TTL);

        assertEquals(List.of(UsernameReservationRepository.emailHash("ñandu@test.com")), ownerHashes("hash"));
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM username_reservation_owners WHERE email_hash LIKE '%@%'", Integer.class));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** {@code created}/{@code expires} de la reserva de un nombre. */
    private Map<String, Instant> reservationTimes(String username) {
        return jdbc().queryForObject(
                "SELECT created_at, expires_at FROM username_reservations WHERE username = ?",
                (rs, row) -> Map.of(
                        "created", rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        "expires", rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                username);
    }

    /** Hashes de los dueños de un nombre, ordenados. */
    private List<String> ownerHashes(String username) {
        return jdbc().queryForList("SELECT email_hash FROM username_reservation_owners"
                + " WHERE username = ? ORDER BY email_hash", String.class, username);
    }

    private int count(String table) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
