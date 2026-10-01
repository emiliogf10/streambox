package com.emilio.streambox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comprueba el esquema que producen las migraciones de Flyway: índices,
 * claves foráneas con borrado en cascada y restricciones de integridad.
 *
 * <p>
 * Que las entidades coincidan con las tablas ya lo verifica Hibernate
 * ({@code ddl-auto=validate}) al arrancar cualquier test de integración.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FlywaySchemaIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void v1EstaAplicadaEnLaTablaDeHistorial() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1' AND \"success\" = TRUE",
                Integer.class);

        assertEquals(1, applied);
    }

    @Test
    void existenLosIndicesParaLasBusquedasDelCatalogo() {
        List<String> indexes = jdbc.queryForList(
                "SELECT LOWER(INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES", String.class);

        assertTrue(indexes.contains("idx_movie_genres_genre_id"), "falta índice por género");
        assertTrue(indexes.contains("idx_favorites_movie_id"), "falta índice de favoritos por película");
        assertTrue(indexes.contains("idx_movies_release_year"), "falta índice por año");
    }

    @Test
    void borrarUnaPeliculaBorraSusFavoritosYGenerosEnCascada() {
        long movieId = insertMovie("Cascade", 2000);
        long userId = insertUser("cascade");
        long genreId = insertGenre("Cascadegenre");

        jdbc.update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);
        jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", userId, movieId);

        jdbc.update("DELETE FROM movies WHERE id = ?", movieId);

        assertEquals(0, count("user_favorite_movies"));
        assertEquals(0, count("movie_genres"));
    }

    @Test
    void borrarUnUsuarioBorraSusFavoritosPeroNoLaPelicula() {
        long movieId = insertMovie("Keep", 2000);
        long userId = insertUser("gone");
        jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", userId, movieId);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertEquals(0, count("user_favorite_movies"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM movies WHERE id = ?", Integer.class, movieId));
    }

    @Test
    void noSePuedeBorrarUnGeneroQueUnaPeliculaUsa() {
        long movieId = insertMovie("InUse", 2000);
        long genreId = insertGenre("Inuse");
        jdbc.update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM genres WHERE id = ?", genreId));
    }

    @Test
    void laBaseDeDatosRechazaAnosYDuracionesImposibles() {
        assertThrows(DataIntegrityViolationException.class, () -> insertMovie("Antigua", 1700));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                        + " VALUES ('Cero', 'd', 0, 2000, 'u', 'v', CURRENT_TIMESTAMP)"));
    }

    @Test
    void laBaseDeDatosRechazaRolesDesconocidos() {
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO users (username, email, password, role, created_at)"
                        + " VALUES ('x', 'x@test.com', 'p', 'SUPERADMIN', CURRENT_TIMESTAMP)"));
    }

    @Test
    void emailYUsernameSonUnicos() {
        insertUser("dup");

        assertThrows(DataIntegrityViolationException.class, () -> insertUser("dup"));
    }

    // --- Utilidades ---

    private long insertMovie(String title, int year) {
        jdbc.update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                + " VALUES (?, 'd', 100, ?, 'u', 'v', CURRENT_TIMESTAMP)", title, year);
        return jdbc.queryForObject("SELECT MAX(id) FROM movies", Long.class);
    }

    private long insertUser(String name) {
        jdbc.update("INSERT INTO users (username, email, password, role, created_at)"
                + " VALUES (?, ?, 'p', 'USER', CURRENT_TIMESTAMP)", name, name + "@test.com");
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }

    private long insertGenre(String name) {
        jdbc.update("INSERT INTO genres (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM genres WHERE name = ?", Long.class, name);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
