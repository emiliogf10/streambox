package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.exception.GenreInUseException;
import com.emilio.streambox.service.GenreService;

/**
 * Edición y borrado de géneros contra <b>PostgreSQL real</b>.
 *
 * <p>
 * Qué protege frente a H2:
 * </p>
 * <ul>
 *   <li>La clave foránea {@code movie_genres → genres} sin cascada rechaza de
 *       verdad el borrado con {@code SQLSTATE 23503}, y el servicio lo traduce a
 *       {@link GenreInUseException} también en una <b>carrera real entre dos
 *       transacciones</b> (el recuento previo dice 0 porque la otra transacción
 *       aún no ha confirmado). H2 tiene otro modelo de bloqueos y no reproduce
 *       la espera.</li>
 *   <li>{@code existsByNameIgnoreCase} se traduce a {@code upper(name) = upper(?)}:
 *       que {@code upper()} trate las letras acentuadas depende de la
 *       <i>collation</i>/<i>ctype</i> de la base de datos (con {@code C} no
 *       cambia la "ó"). La imagen {@code postgres:16} usa {@code en_US.utf8}.</li>
 *   <li>Un nombre que crece al normalizar y no cabría en {@code VARCHAR(50)}
 *       (PostgreSQL respondería {@code 22001}): no puede acabar en 500.</li>
 * </ul>
 *
 * <p>
 * Los datos se confirman de verdad y se limpian antes y después de cada test.
 * </p>
 */
class PostgresGenreIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private GenreService genreService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    private String adminToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = tokenFor(saveUser("pgenreadmin", Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Borrado de un género en uso
    // ------------------------------------------------------------------

    /** Camino normal: el recuento (COUNT con JOIN) funciona en PostgreSQL y da 409 con la cifra. */
    @Test
    void borrarUnGeneroEnUsoDa409ConElRecuento() throws Exception {
        Genre drama = saveGenre("Drama");
        saveMovie("Uno", 2000, drama);
        saveMovie("Dos", 2001, drama);

        mockMvc.perform(delete("/api/genres/{id}", drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value(
                        "No se puede eliminar el género \"Drama\": lo usan 2 películas. "
                                + "Quítalo de esas películas antes de borrarlo."));

        assertTrue(genreRepository.existsById(drama.getId()));
    }

    /**
     * Un {@code DELETE} que se salta el recuento (por repositorio + {@code flush},
     * lo mismo que hace el servicio) lo rechaza la clave foránea con el
     * {@code SQLSTATE 23503} de PostgreSQL, envuelto en
     * {@link DataIntegrityViolationException}: es exactamente lo que
     * {@code GenreService.deleteGenre} captura.
     */
    @Test
    void elBorradoDirectoDeUnGeneroAsignadoDa23503() {
        Genre drama = saveGenre("Drama");
        saveMovie("Uno", 2000, drama);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(status -> {
                    genreRepository.deleteById(drama.getId());
                    genreRepository.flush();
                }));

        assertEquals("23503", sqlState(error));
        assertTrue(genreRepository.existsById(drama.getId()));
    }

    /**
     * Carrera real entre dos transacciones:
     * <ol>
     *   <li>T1 asigna el género a una película ({@code INSERT} en
     *       {@code movie_genres}) y <b>no confirma</b>. Al comprobar la clave
     *       foránea, PostgreSQL bloquea la fila del género ({@code FOR KEY SHARE}).</li>
     *   <li>T2 ({@code GenreService.deleteGenre}) cuenta películas: 0, porque no
     *       ve lo no confirmado. Ejecuta el {@code DELETE} y se queda
     *       <b>esperando</b> el bloqueo de T1.</li>
     *   <li>T1 confirma. T2 continúa, la clave foránea encuentra la fila nueva y
     *       PostgreSQL rechaza el borrado ({@code 23503}).</li>
     * </ol>
     * El servicio debe responder con {@link GenreInUseException} en su variante
     * "sin cifra" (prueba de que el recuento dio 0 y la que saltó fue la
     * restricción), y no dejar el género borrado ni la película sin él.
     */
    @Test
    void unaAsignacionConcurrenteDuranteElBorradoSeTraduceAGeneroEnUso() throws Exception {
        Genre drama = saveGenre("Drama");
        Genre comedy = saveGenre("Comedia");
        Movie movie = saveMovie("Uno", 2000, comedy);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch inserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> assigner = pool.submit(() -> tx.execute(status -> {
                jdbc.update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)",
                        movie.getId(), drama.getId());
                inserted.countDown();
                // No se confirma hasta ver al borrado bloqueado esperando esta fila.
                return waitForBlockedSession();
            }));

            Future<?> deleter = pool.submit(() -> {
                await(inserted);
                genreService.deleteGenre(drama.getId());
                return null;
            });

            assertTrue(assigner.get(30, TimeUnit.SECONDS),
                    "El DELETE del género no llegó a quedarse esperando el bloqueo de la otra transacción");

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> deleter.get(30, TimeUnit.SECONDS));
            GenreInUseException inUse = assertInstanceOf(GenreInUseException.class, thrown.getCause());
            assertEquals("No se puede eliminar el género \"Drama\": alguna película o serie lo tiene asignado. "
                    + "Quítalo de esas películas o series antes de borrarlo.", inUse.getMessage());
        } finally {
            pool.shutdownNow();
        }

        assertTrue(genreRepository.existsById(drama.getId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM movie_genres WHERE movie_id = ?", Integer.class, movie.getId()));
    }

    // ------------------------------------------------------------------
    // Duplicados sin distinguir mayúsculas (upper() de PostgreSQL)
    // ------------------------------------------------------------------

    /**
     * Fila heredada {@code "CIENCIA FICCIÓN"} frente a {@code "ciencia ficción"}
     * (normalizado: {@code "Ciencia ficción"}). La {@code UNIQUE} no lo detecta
     * (los textos difieren, también en "Ó"/"ó"), así que solo lo frena
     * {@code upper()} en PostgreSQL, que debe pasar "ó" a "Ó".
     */
    @Test
    void unAltaQueSoloDifiereEnMayusculasAcentuadasDeUnaFilaHeredadaDa409() throws Exception {
        saveGenre("CIENCIA FICCIÓN");

        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ciencia ficción\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"));

        assertEquals(1, genreRepository.count());
    }

    /** Caso inverso: existe {@code "Ciencia ficción"} y se pide {@code "CIENCIA FICCIÓN"}. */
    @Test
    void unAltaEnMayusculasConAcentosDeUnGeneroExistenteDa409() throws Exception {
        saveGenre("Ciencia ficción");

        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CIENCIA FICCIÓN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Ya existe un género con el nombre \"Ciencia ficción\""));

        assertEquals(1, genreRepository.count());
    }

    /** La edición usa {@code existsByNameIgnoreCaseAndIdNot}: misma comprobación con acentos. */
    @Test
    void renombrarAlNombreConOtrasMayusculasAcentuadasDeOtroGeneroDa409() throws Exception {
        saveGenre("CIENCIA FICCIÓN");
        Genre fantasy = saveGenre("Fantasía");

        mockMvc.perform(put("/api/genres/{id}", fantasy.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ciencia ficción\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"));

        assertEquals("Fantasía", genreRepository.findById(fantasy.getId()).orElseThrow().getName());
    }

    /**
     * Nombre de 50 caracteres que crece al normalizar ({@code "A" + 49 × "İ"}
     * → 99 caracteres): hoy lo rechaza el servicio al validar la longitud del
     * nombre ya normalizado, antes de llegar a la columna; si esa validación
     * faltara, sería PostgreSQL quien lo rechazara ({@code 22001}). En ningún
     * caso puede responder 500 ni guardar nada. El código exacto (400
     * {@code VALIDATION_ERROR}) lo fija {@code GenreEdgeCasesIntegrationTest}.
     */
    @Test
    void unNombreQueCreceAlNormalizarNoDa500NiSeGuarda() throws Exception {
        int statusCode = mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A" + "İ".repeat(49) + "\"}"))
                .andReturn().getResponse().getStatus();

        assertTrue(statusCode >= 400 && statusCode < 500, "Se esperaba un 4xx y llegó " + statusCode);
        assertEquals(0, genreRepository.count());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Espera (hasta 10 s) a que otra sesión de esta base de datos esté
     * bloqueada esperando un <i>lock</i>. Usa una conexión propia en
     * autocommit: dentro de una transacción {@code pg_stat_activity} devuelve
     * siempre la misma foto y nunca vería el cambio.
     *
     * @return {@code true} si se observó la sesión bloqueada
     */
    private boolean waitForBlockedSession() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM pg_stat_activity "
                                + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return true;
                    }
                }
                Thread.sleep(50);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            fail("No se pudo consultar pg_stat_activity: " + e.getMessage());
            return false;
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("La otra transacción no llegó a insertar la asignación");
        }
    }
}
