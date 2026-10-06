package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tablas de series de {@code V3} ({@code series}, {@code series_genres},
 * {@code episodes}, {@code user_favorite_series}) sobre un <b>PostgreSQL real</b>.
 *
 * <p>
 * Es el equivalente, para series, de {@code PostgresSchemaIntegrationTest}
 * (tipos reales, claves, índices, políticas de borrado y cada restricción con
 * su {@code SQLSTATE}) y de la parte de repositorio de
 * {@code SeriesRepositoryIntegrationTest} que depende del motor: las consultas
 * nativas de favoritos (en especial el {@code COUNT(*) > 0} que PostgreSQL
 * devuelve como {@code boolean}) y que el detalle siga costando dos consultas.
 * </p>
 *
 * <p>
 * Los datos se confirman de verdad (sin {@code @Transactional}) y se limpian
 * antes y después de cada test, porque el contenedor es compartido. Sin Docker
 * la clase se omite (ver {@link PostgresIntegrationTestSupport}).
 * </p>
 */
class PostgresSeriesSchemaIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Tipos, longitudes y obligatoriedad
    // ------------------------------------------------------------------

    /** Las fechas de creación nacen ya como {@code timestamp with time zone}. */
    @Test
    void createdAtEsTimestampConZonaHorariaEnSeriesYEpisodios() {
        for (String table : List.of("series", "episodes")) {
            String type = jdbc.queryForObject(
                    "SELECT data_type FROM information_schema.columns"
                            + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'created_at'",
                    String.class, table);
            assertEquals("timestamp with time zone", type, table + ".created_at");
        }
    }

    /**
     * Longitudes de los {@code VARCHAR} y qué columnas admiten {@code NULL}:
     * solo {@code series.end_year} ("en emisión") y {@code episodes.description}
     * (sinopsis opcional).
     */
    @Test
    void lasLongitudesYLaObligatoriedadDeLasColumnasSonLasDeLasEntidades() {
        Map<String, Integer> expectedLengths = Map.of(
                "series.title", 150, "series.description", 1000, "series.image_url", 500,
                "episodes.title", 150, "episodes.description", 1000, "episodes.video_url", 500);

        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT table_name, column_name, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name IN ('series','series_genres','episodes','user_favorite_series')");

        for (Map.Entry<String, Integer> expected : expectedLengths.entrySet()) {
            Map<String, Object> column = columns.stream()
                    .filter(c -> (c.get("table_name") + "." + c.get("column_name")).equals(expected.getKey()))
                    .findFirst().orElseThrow(() -> new AssertionError("falta la columna " + expected.getKey()));
            assertEquals(expected.getValue(), column.get("character_maximum_length"), expected.getKey());
        }
        Set<String> nullable = Set.copyOf(columns.stream()
                .filter(c -> "YES".equals(c.get("is_nullable")))
                .map(c -> c.get("table_name") + "." + c.get("column_name"))
                .toList());
        assertEquals(Set.of("series.end_year", "episodes.description"), nullable);
    }

    /** Identificadores {@code BIGINT GENERATED BY DEFAULT AS IDENTITY}, como en V1. */
    @Test
    void losIdentificadoresSonBigintIdentity() {
        for (String table : List.of("series", "episodes")) {
            Map<String, Object> id = jdbc.queryForMap(
                    "SELECT data_type, is_identity, identity_generation FROM information_schema.columns"
                            + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'id'", table);
            assertEquals("bigint", id.get("data_type"), table);
            assertEquals("YES", id.get("is_identity"), table);
            assertEquals("BY DEFAULT", id.get("identity_generation"), table);
        }
    }

    // ------------------------------------------------------------------
    // Claves, índices y restricciones
    // ------------------------------------------------------------------

    /** Claves primarias con nombre; las de las tablas de unión, compuestas y en este orden. */
    @Test
    void lasClavesPrimariasTienenNombreYElOrdenCorrecto() {
        assertEquals(List.of("series_id", "genre_id"), primaryKeyColumns("series_genres", "pk_series_genres"));
        assertEquals(List.of("user_id", "series_id"), primaryKeyColumns("user_favorite_series", "pk_user_favorite_series"));
        assertEquals(List.of("id"), primaryKeyColumns("series", "pk_series"));
        assertEquals(List.of("id"), primaryKeyColumns("episodes", "pk_episodes"));
    }

    /**
     * Los índices de V3 sobre las columnas correctas (definición real de
     * {@code pg_indexes}). El índice único de episodios empieza por
     * {@code series_id}: es el que usan el listado ordenado, el {@code EXISTS}
     * de "serie con episodios" y la cascada al borrar una serie.
     */
    @Test
    void existenLosIndicesDeSeriesSobreLasColumnasCorrectas() {
        Map<String, String> definitions = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));

        assertIndex(definitions, "idx_series_genres_genre_id", "ON public.series_genres USING btree (genre_id)");
        assertIndex(definitions, "idx_favorite_series_series_id",
                "ON public.user_favorite_series USING btree (series_id)");
        assertIndex(definitions, "idx_series_release_year", "ON public.series USING btree (release_year)");
        assertIndex(definitions, "uk_episodes_series_season_episode",
                "UNIQUE INDEX uk_episodes_series_season_episode ON public.episodes USING btree"
                        + " (series_id, season_number, episode_number)");
    }

    /** Las claves foráneas de V3 con su política de borrado. */
    @Test
    void lasClavesForaneasTienenLaPoliticaDeBorradoEsperada() {
        Map<String, String> rules = new HashMap<>();
        jdbc.queryForList("SELECT constraint_name, delete_rule FROM information_schema.referential_constraints"
                        + " WHERE constraint_schema = 'public'")
                .forEach(r -> rules.put((String) r.get("constraint_name"), (String) r.get("delete_rule")));

        assertEquals("CASCADE", rules.get("fk_series_genres_series"));
        assertEquals("NO ACTION", rules.get("fk_series_genres_genre"), "un género en uso no se puede borrar");
        assertEquals("CASCADE", rules.get("fk_episodes_series"));
        assertEquals("CASCADE", rules.get("fk_favorite_series_user"));
        assertEquals("CASCADE", rules.get("fk_favorite_series_series"));
    }

    /** Existen los cinco CHECK de V3 con su nombre. */
    @Test
    void existenLasRestriccionesCheckConNombre() {
        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
                String.class));

        assertTrue(checks.containsAll(Set.of("ck_series_release_year", "ck_series_end_year",
                "ck_episodes_season_number", "ck_episodes_episode_number", "ck_episodes_duration")),
                "checks: " + checks);
    }

    /** Años imposibles de una serie: 23514 con el nombre del CHECK que falla. */
    @Test
    void laBaseDeDatosRechazaAnosDeSerieImposibles() {
        assertCheck("ck_series_release_year", () -> insertSeries("Antigua", 1887, null));
        assertCheck("ck_series_release_year", () -> insertSeries("Futura", 2101, null));
        assertCheck("ck_series_end_year", () -> insertSeries("Al reves", 2010, 2009));
        assertCheck("ck_series_end_year", () -> insertSeries("Eterna", 2010, 2101));
        assertEquals(0, count("series"));

        // Extremos válidos y "en emisión"
        insertSeries("En emision", 2020, null);
        insertSeries("Un ano", 2020, 2020);
        insertSeries("Limites", 1888, 2100);
        assertEquals(3, count("series"));
    }

    /** Temporada, número y duración deben ser positivos: 23514 con su CHECK. */
    @Test
    void laBaseDeDatosRechazaTemporadasEpisodiosYDuracionesImposibles() {
        long seriesId = insertSeries("Checks", 2010, null);

        assertCheck("ck_episodes_season_number", () -> insertEpisode(seriesId, 0, 1, 40));
        assertCheck("ck_episodes_episode_number", () -> insertEpisode(seriesId, 1, 0, 40));
        assertCheck("ck_episodes_duration", () -> insertEpisode(seriesId, 1, 1, 0));
        assertCheck("ck_episodes_duration", () -> insertEpisode(seriesId, 1, 1, -5));
        assertEquals(0, count("episodes"));
    }

    /** Dos episodios en la misma posición de una serie: 23505 con el nombre del índice único. */
    @Test
    void noPuedeHaberDosEpisodiosConLaMismaTemporadaYNumeroEnUnaSerie() {
        long seriesId = insertSeries("Unica", 2010, null);
        long otherId = insertSeries("Otra", 2010, null);
        insertEpisode(seriesId, 1, 1, 40);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> insertEpisode(seriesId, 1, 1, 50));

        assertEquals(UNIQUE_VIOLATION, sqlState(error));
        assertTrue(error.getMessage().contains("uk_episodes_series_season_episode"), error.getMessage());
        insertEpisode(seriesId, 2, 1, 40);
        insertEpisode(otherId, 1, 1, 40);
        assertEquals(3, count("episodes"));
    }

    /** NOT NULL real (23502) en una columna obligatoria de episodios. */
    @Test
    void laBaseDeDatosRechazaNulosEnColumnasObligatorias() {
        long seriesId = insertSeries("Nulos", 2010, null);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, duration,"
                        + " video_url, created_at) VALUES (?, 1, 1, 't', 40, NULL, now())", seriesId));

        assertEquals(NOT_NULL_VIOLATION, sqlState(error));
    }

    /** Un episodio de una serie inexistente: 23503. */
    @Test
    void unEpisodioNecesitaUnaSerieExistente() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> insertEpisode(987654L, 1, 1, 40));

        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(error));
        assertTrue(error.getMessage().contains("fk_episodes_series"), error.getMessage());
    }

    // ------------------------------------------------------------------
    // Cascadas
    // ------------------------------------------------------------------

    /** Borrar una serie arrastra episodios, géneros de la serie y favoritos; no usuarios ni géneros. */
    @Test
    void borrarUnaSerieBorraSusEpisodiosGenerosYFavoritosEnCascada() {
        long seriesId = insertSeries("Cascada", 2010, null);
        User user = saveUser("cascada", Role.USER);
        Genre genre = saveGenre("Cascada");
        insertEpisode(seriesId, 1, 1, 40);
        insertEpisode(seriesId, 1, 2, 40);
        jdbc.update("INSERT INTO series_genres (series_id, genre_id) VALUES (?, ?)", seriesId, genre.getId());
        jdbc.update("INSERT INTO user_favorite_series (user_id, series_id) VALUES (?, ?)", user.getId(), seriesId);

        jdbc.update("DELETE FROM series WHERE id = ?", seriesId);

        assertEquals(0, count("episodes"));
        assertEquals(0, count("series_genres"));
        assertEquals(0, count("user_favorite_series"));
        assertEquals(1, count("users"));
        assertEquals(1, count("genres"));
    }

    /** Borrar un usuario arrastra sus favoritos de series, pero no la serie. */
    @Test
    void borrarUnUsuarioBorraSusFavoritosDeSeriesPeroNoLaSerie() {
        long seriesId = insertSeries("Sigue", 2010, null);
        User user = saveUser("gone", Role.USER);
        jdbc.update("INSERT INTO user_favorite_series (user_id, series_id) VALUES (?, ?)", user.getId(), seriesId);

        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());

        assertEquals(0, count("user_favorite_series"));
        assertEquals(1, count("series"));
    }

    /** Un género que usa una serie no se puede borrar: 23503 con el nombre de la FK. */
    @Test
    void noSePuedeBorrarUnGeneroQueUnaSerieUsa() {
        long seriesId = insertSeries("Usa genero", 2010, null);
        Genre genre = saveGenre("Enuso");
        jdbc.update("INSERT INTO series_genres (series_id, genre_id) VALUES (?, ?)", seriesId, genre.getId());

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM genres WHERE id = ?", genre.getId()));

        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(error));
        assertTrue(error.getMessage().contains("fk_series_genres_genre"), error.getMessage());
    }

    // ------------------------------------------------------------------
    // Consultas de los repositorios que dependen del motor
    // ------------------------------------------------------------------

    /**
     * Las consultas nativas de favoritos en PostgreSQL: el {@code COUNT(*) > 0}
     * llega como {@code boolean}, el duplicado es un 23505 sobre
     * {@code pk_user_favorite_series} y {@code DELETE} devuelve las filas borradas.
     */
    @Test
    void lasConsultasNativasDeFavoritosFuncionanEnPostgres() {
        User user = saveUser("fan", Role.USER);
        Series series = saveSeries("Favorita", 2015);

        assertFalse(seriesRepository.isFavorite(user.getId(), series.getId()));
        tx.executeWithoutResult(status -> seriesRepository.addFavorite(user.getId(), series.getId()));
        assertTrue(seriesRepository.isFavorite(user.getId(), series.getId()));

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, () ->
                tx.executeWithoutResult(status -> seriesRepository.addFavorite(user.getId(), series.getId())));
        assertEquals(UNIQUE_VIOLATION, sqlState(error));
        assertTrue(error.getMessage().contains("pk_user_favorite_series"), error.getMessage());

        assertEquals(1, (int) tx.execute(status -> seriesRepository.removeFavorite(user.getId(), series.getId())));
        assertEquals(0, (int) tx.execute(status -> seriesRepository.clearFavorites(user.getId())));
    }

    /** La lista de series sale ordenada por título y solo con las del usuario. */
    @Test
    void laListaDeSeriesVaOrdenadaPorTitulo() {
        User user = saveUser("fan", Role.USER);
        User other = saveUser("otro", Role.USER);
        Series zeta = saveSeries("Zeta", 2015);
        Series alfa = saveSeries("Alfa", 2015);
        Series ajena = saveSeries("Ajena", 2015);
        tx.executeWithoutResult(status -> {
            seriesRepository.addFavorite(user.getId(), zeta.getId());
            seriesRepository.addFavorite(user.getId(), alfa.getId());
            seriesRepository.addFavorite(other.getId(), ajena.getId());
        });

        assertEquals(List.of("Alfa", "Zeta"),
                seriesRepository.findFavoritesByUserId(user.getId()).stream().map(Series::getTitle).toList());
    }

    /** Episodios ordenados numéricamente (10 después de 2) y la serie vacía oculta. */
    @Test
    void losEpisodiosSalenOrdenadosYUnaSerieVaciaNoEsVisible() {
        Series series = saveSeries("Orden", 2015);
        Series empty = saveSeries("Vacia", 2015);
        saveEpisode(series, 10, 1);
        saveEpisode(series, 2, 1);
        saveEpisode(series, 1, 2);
        saveEpisode(series, 1, 1);

        List<String> order = episodeRepository.findAllBySeriesIdOrdered(series.getId()).stream()
                .map(e -> e.getSeasonNumber() + "x" + e.getEpisodeNumber())
                .toList();

        assertEquals(List.of("1x1", "1x2", "2x1", "10x1"), order);
        assertTrue(seriesRepository.findVisibleById(series.getId()).isPresent());
        assertTrue(seriesRepository.findVisibleById(empty.getId()).isEmpty());
    }

    /** El detalle completo (serie, géneros y 12 episodios) cuesta dos consultas también en PostgreSQL. */
    @Test
    void elDetalleCompletoCuestaDosConsultas() {
        Series series = saveSeries("Larga", 2015, saveGenre("Drama"), saveGenre("Comedia"));
        for (int number = 1; number <= 12; number++) {
            saveEpisode(series, 1 + number / 7, number);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        tx.executeWithoutResult(status -> {
            Series detail = seriesRepository.findVisibleById(series.getId()).orElseThrow();
            List<Episode> episodes = episodeRepository.findAllBySeriesIdOrdered(series.getId());
            assertEquals(2, detail.getGenres().size());
            episodes.forEach(e -> assertEquals(series.getId(), e.getSeries().getId()));
        });

        assertEquals(2, statistics.getPrepareStatementCount());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private void assertCheck(String constraint, Runnable insert) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, insert::run);
        assertEquals(CHECK_VIOLATION, sqlState(error), constraint);
        assertTrue(error.getMessage().contains(constraint), error.getMessage());
    }

    private static void assertIndex(Map<String, String> definitions, String name, String expected) {
        String definition = definitions.get(name);
        assertTrue(definition != null && definition.contains(expected), name + ": " + definition);
    }

    private List<String> primaryKeyColumns(String table, String constraintName) {
        return jdbc.queryForList(
                "SELECT k.column_name FROM information_schema.table_constraints c"
                        + " JOIN information_schema.key_column_usage k"
                        + "   ON k.constraint_name = c.constraint_name AND k.table_schema = c.table_schema"
                        + " WHERE c.constraint_type = 'PRIMARY KEY' AND c.table_schema = 'public'"
                        + " AND c.table_name = ? AND c.constraint_name = ?"
                        + " ORDER BY k.ordinal_position", String.class, table, constraintName);
    }

    private long insertSeries(String title, int releaseYear, Integer endYear) {
        return jdbc.queryForObject(
                "INSERT INTO series (title, description, release_year, end_year, image_url, created_at)"
                        + " VALUES (?, 'd', ?, ?, 'u', now()) RETURNING id",
                Long.class, title, releaseYear, endYear);
    }

    private void insertEpisode(long seriesId, int season, int number, int duration) {
        jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, description,"
                + " duration, video_url, created_at) VALUES (?, ?, ?, 't', 'd', ?, 'v', now())",
                seriesId, season, number, duration);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
