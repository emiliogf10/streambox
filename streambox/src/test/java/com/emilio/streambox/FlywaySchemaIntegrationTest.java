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
 * claves foráneas con borrado en cascada y restricciones de integridad
 * (películas en {@code V1}/{@code V2}; series y episodios en {@code V3}).
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

    // --- V3: series y episodios ---

    @Test
    void v3EstaAplicadaEnLaTablaDeHistorial() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '3' AND \"success\" = TRUE",
                Integer.class);

        assertEquals(1, applied);
    }

    @Test
    void existenLosIndicesDeSeries() {
        List<String> indexes = jdbc.queryForList(
                "SELECT LOWER(INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES", String.class);

        assertTrue(indexes.contains("idx_series_genres_genre_id"), "falta índice de series por género");
        assertTrue(indexes.contains("idx_favorite_series_series_id"), "falta índice de favoritos por serie");
        assertTrue(indexes.contains("idx_series_release_year"), "falta índice de series por año");
    }

    /**
     * Las políticas de borrado de las claves foráneas nuevas: todo cuelga de la
     * serie (o del usuario) en cascada, salvo el género, que no se puede borrar
     * si alguna serie lo usa.
     */
    @Test
    void lasClavesForaneasDeSeriesTienenLaPoliticaDeBorradoEsperada() {
        java.util.Map<String, String> rules = new java.util.HashMap<>();
        jdbc.queryForList("SELECT LOWER(CONSTRAINT_NAME) AS NAME, DELETE_RULE AS RULE"
                        + " FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS")
                .forEach(r -> rules.put((String) r.get("NAME"), (String) r.get("RULE")));

        assertEquals("CASCADE", rules.get("fk_series_genres_series"));
        assertEquals("NO ACTION", rules.get("fk_series_genres_genre"));
        assertEquals("CASCADE", rules.get("fk_episodes_series"));
        assertEquals("CASCADE", rules.get("fk_favorite_series_user"));
        assertEquals("CASCADE", rules.get("fk_favorite_series_series"));
    }

    @Test
    void borrarUnaSerieBorraSusEpisodiosGenerosYFavoritosEnCascada() {
        long seriesId = insertSeries("Cascada", 2010, null);
        long userId = insertUser("seriescascade");
        long genreId = insertGenre("Seriescascade");
        insertEpisode(seriesId, 1, 1, 40);
        insertEpisode(seriesId, 1, 2, 40);
        jdbc.update("INSERT INTO series_genres (series_id, genre_id) VALUES (?, ?)", seriesId, genreId);
        jdbc.update("INSERT INTO user_favorite_series (user_id, series_id) VALUES (?, ?)", userId, seriesId);

        jdbc.update("DELETE FROM series WHERE id = ?", seriesId);

        assertEquals(0, count("episodes"));
        assertEquals(0, count("series_genres"));
        assertEquals(0, count("user_favorite_series"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM genres WHERE id = ?", Integer.class, genreId));
    }

    @Test
    void borrarUnUsuarioBorraSusFavoritosDeSeriesPeroNoLaSerie() {
        long seriesId = insertSeries("Sigue", 2010, null);
        long userId = insertUser("seriesgone");
        jdbc.update("INSERT INTO user_favorite_series (user_id, series_id) VALUES (?, ?)", userId, seriesId);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertEquals(0, count("user_favorite_series"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM series WHERE id = ?", Integer.class, seriesId));
    }

    @Test
    void noSePuedeBorrarUnGeneroQueUnaSerieUsa() {
        long seriesId = insertSeries("Usa genero", 2010, null);
        long genreId = insertGenre("Seriesinuse");
        jdbc.update("INSERT INTO series_genres (series_id, genre_id) VALUES (?, ?)", seriesId, genreId);

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM genres WHERE id = ?", genreId));
    }

    @Test
    void laBaseDeDatosRechazaAnosDeSerieImposibles() {
        assertThrows(DataIntegrityViolationException.class, () -> insertSeries("Antigua", 1887, null));
        assertThrows(DataIntegrityViolationException.class, () -> insertSeries("Futura", 2101, null));
        // Termina antes de empezar
        assertThrows(DataIntegrityViolationException.class, () -> insertSeries("Al reves", 2010, 2009));
        // Fin más allá del tope
        assertThrows(DataIntegrityViolationException.class, () -> insertSeries("Eterna", 2010, 2101));
        assertEquals(0, count("series"));
    }

    @Test
    void laBaseDeDatosAceptaSeriesEnEmisionYDeUnSoloAno() {
        insertSeries("En emision", 2020, null);
        insertSeries("Un ano", 2020, 2020);
        insertSeries("Limites", 1888, 2100);

        assertEquals(3, count("series"));
    }

    @Test
    void laBaseDeDatosRechazaTemporadasEpisodiosYDuracionesImposibles() {
        long seriesId = insertSeries("Checks", 2010, null);

        assertThrows(DataIntegrityViolationException.class, () -> insertEpisode(seriesId, 0, 1, 40));
        assertThrows(DataIntegrityViolationException.class, () -> insertEpisode(seriesId, 1, 0, 40));
        assertThrows(DataIntegrityViolationException.class, () -> insertEpisode(seriesId, 1, 1, 0));
        assertThrows(DataIntegrityViolationException.class, () -> insertEpisode(seriesId, -1, 1, 40));
        assertEquals(0, count("episodes"));
    }

    @Test
    void noPuedeHaberDosEpisodiosConLaMismaTemporadaYNumeroEnUnaSerie() {
        long seriesId = insertSeries("Unica", 2010, null);
        long otherSeriesId = insertSeries("Otra", 2010, null);
        insertEpisode(seriesId, 1, 1, 40);

        assertThrows(DataIntegrityViolationException.class, () -> insertEpisode(seriesId, 1, 1, 50));

        // El mismo número en otra temporada o en otra serie sí vale
        insertEpisode(seriesId, 2, 1, 40);
        insertEpisode(otherSeriesId, 1, 1, 40);
        assertEquals(3, count("episodes"));
    }

    @Test
    void laSinopsisDelEpisodioEsOpcional() {
        long seriesId = insertSeries("Sin sinopsis", 2010, null);

        jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, description,"
                + " duration, video_url, created_at) VALUES (?, 1, 1, 't', NULL, 30, 'v', CURRENT_TIMESTAMP)", seriesId);

        assertEquals(1, count("episodes"));
    }

    @Test
    void unEpisodioNecesitaUnaSerieExistente() {
        assertThrows(DataIntegrityViolationException.class, () -> insertEpisode(987654L, 1, 1, 40));
    }

    @Test
    void unaSerieNoPuedeEstarDosVecesEnLaListaDeUnUsuario() {
        long seriesId = insertSeries("Duplicada", 2010, null);
        long userId = insertUser("seriesdup");
        jdbc.update("INSERT INTO user_favorite_series (user_id, series_id) VALUES (?, ?)", userId, seriesId);

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO user_favorite_series (user_id, series_id) VALUES (?, ?)", userId, seriesId));
    }

    // --- Utilidades ---

    private long insertSeries(String title, int releaseYear, Integer endYear) {
        jdbc.update("INSERT INTO series (title, description, release_year, end_year, image_url, created_at)"
                + " VALUES (?, 'd', ?, ?, 'u', CURRENT_TIMESTAMP)", title, releaseYear, endYear);
        return jdbc.queryForObject("SELECT MAX(id) FROM series", Long.class);
    }

    private void insertEpisode(long seriesId, int season, int number, int duration) {
        jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, description,"
                + " duration, video_url, created_at) VALUES (?, ?, ?, 't', 'd', ?, 'v', CURRENT_TIMESTAMP)",
                seriesId, season, number, duration);
    }

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
