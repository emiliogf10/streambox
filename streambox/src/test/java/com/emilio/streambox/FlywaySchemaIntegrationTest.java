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

import com.emilio.streambox.migration.TrailerUrlMigrationScenario;
import com.emilio.streambox.migration.UserAvatarSchemaCases;

/**
 * Comprueba el esquema que producen las migraciones de Flyway: índices,
 * claves foráneas con borrado en cascada y restricciones de integridad
 * (películas en {@code V1}/{@code V2}; series y episodios en {@code V3};
 * refresh tokens en {@code V4}; tráiler y vídeo opcional en {@code V6};
 * icono de perfil en {@code V7}; «me gusta» en {@code V8}, cuyas consultas
 * se prueban en {@code repository.LikesRepositoryIntegrationTest}).
 * El movimiento de datos de la {@code V6} se prueba aparte, partiendo de una
 * base en {@code V5}: {@code migration.TrailerUrlMigrationH2Test} y su gemelo
 * de PostgreSQL.
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

    // --- V4: refresh tokens ---

    /** Hash válido: 64 caracteres hexadecimales en minúsculas (lo que guarda el servicio). */
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "0123456789abcdef".repeat(4);

    @Test
    void v4EstaAplicadaEnLaTablaDeHistorial() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '4' AND \"success\" = TRUE",
                Integer.class);

        assertEquals(1, applied);
    }

    @Test
    void existenLosIndicesDeRefreshTokens() {
        List<String> indexes = jdbc.queryForList(
                "SELECT LOWER(INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES", String.class);

        assertTrue(indexes.contains("idx_refresh_tokens_user_id"), "falta índice de tokens por usuario");
        assertTrue(indexes.contains("idx_refresh_tokens_family_id"), "falta índice de tokens por familia");
        assertTrue(indexes.contains("idx_refresh_tokens_replaced_by_id"), "falta índice del sucesor (ON DELETE SET NULL)");
    }

    /** Borrar el usuario borra sus tokens; borrar el sucesor deja a NULL el enlace del predecesor. */
    @Test
    void lasClavesForaneasDeRefreshTokensTienenLaPoliticaDeBorradoEsperada() {
        java.util.Map<String, String> rules = new java.util.HashMap<>();
        jdbc.queryForList("SELECT LOWER(CONSTRAINT_NAME) AS NAME, DELETE_RULE AS RULE"
                        + " FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS")
                .forEach(r -> rules.put((String) r.get("NAME"), (String) r.get("RULE")));

        assertEquals("CASCADE", rules.get("fk_refresh_tokens_user"));
        assertEquals("SET NULL", rules.get("fk_refresh_tokens_replaced_by"));
    }

    @Test
    void borrarUnUsuarioBorraSusRefreshTokensYNoLosDeOtros() {
        long userId = insertUser("tokengone");
        long otherId = insertUser("tokenstays");
        insertToken(userId, HASH_A, java.util.UUID.randomUUID(), null);
        insertToken(otherId, HASH_B, java.util.UUID.randomUUID(), null);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertEquals(1, count("refresh_tokens"));
        assertEquals(otherId, jdbc.queryForObject("SELECT user_id FROM refresh_tokens", Long.class));
    }

    @Test
    void borrarElSucesorDejaAlPredecesorRevocadoYSinEnlace() {
        long userId = insertUser("tokenchain");
        java.util.UUID family = java.util.UUID.randomUUID();
        long next = insertToken(userId, HASH_B, family, null);
        long previous = insertToken(userId, HASH_A, family, null);
        jdbc.update("UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP, replaced_by_id = ? WHERE id = ?",
                next, previous);

        jdbc.update("DELETE FROM refresh_tokens WHERE id = ?", next);

        assertEquals(null, jdbc.queryForObject("SELECT replaced_by_id FROM refresh_tokens WHERE id = ?",
                Long.class, previous));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE id = ? AND revoked_at IS NOT NULL",
                Integer.class, previous));
    }

    @Test
    void elHashDelTokenEsUnico() {
        long userId = insertUser("tokendup");
        insertToken(userId, HASH_A, java.util.UUID.randomUUID(), null);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertToken(userId, HASH_A, java.util.UUID.randomUUID(), null));
    }

    /**
     * El CHECK de formato impide guardar el token en claro por error: solo
     * acepta 64 caracteres hexadecimales en minúsculas.
     */
    @Test
    void laBaseDeDatosRechazaHashesQueNoSonSha256EnHexadecimal() {
        long userId = insertUser("tokenformat");
        java.util.UUID family = java.util.UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () -> insertToken(userId, "a".repeat(63), family, null));
        assertThrows(DataIntegrityViolationException.class, () -> insertToken(userId, "A".repeat(64), family, null));
        // Un token base64url de 64 caracteres (como sería uno en claro) tiene letras fuera de a-f
        assertThrows(DataIntegrityViolationException.class,
                () -> insertToken(userId, "Zq3-_x" + "a".repeat(58), family, null));
        assertThrows(DataIntegrityViolationException.class, () -> insertToken(userId, "g".repeat(64), family, null));
        assertEquals(0, count("refresh_tokens"));
    }

    @Test
    void laBaseDeDatosRechazaCaducidadesImposibles() {
        long userId = insertUser("tokendates");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);

        // Caduca en el mismo instante en que nace
        assertThrows(DataIntegrityViolationException.class,
                () -> insertToken(userId, HASH_A, now, now, now.plusDays(1)));
        // Caduca después que su familia
        assertThrows(DataIntegrityViolationException.class,
                () -> insertToken(userId, HASH_A, now, now.plusDays(2), now.plusDays(1)));
        assertEquals(0, count("refresh_tokens"));

        // Límite válido: caduca justo cuando la familia
        insertToken(userId, HASH_A, now, now.plusDays(1), now.plusDays(1));
        assertEquals(1, count("refresh_tokens"));
    }

    @Test
    void unTokenConSucesorDebeEstarRevocadoYNoPuedeSucederseASiMismo() {
        long userId = insertUser("tokenrevoked");
        java.util.UUID family = java.util.UUID.randomUUID();
        long next = insertToken(userId, HASH_B, family, null);
        long previous = insertToken(userId, HASH_A, family, null);

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("UPDATE refresh_tokens SET replaced_by_id = ? WHERE id = ?", next, previous));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP, replaced_by_id = id WHERE id = ?",
                        previous));
    }

    @Test
    void unRefreshTokenNecesitaUnUsuarioExistente() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertToken(987654L, HASH_A, java.util.UUID.randomUUID(), null));
    }

    // --- V5: búsqueda por título sin acentos (versión de H2, db/vendor/h2) ---

    /**
     * La V5 de H2 (src/main/resources/db/vendor/h2) se aplica: demuestra que la
     * ubicación {@code db/vendor/{vendor}} de {@code spring.flyway.locations} se
     * resuelve a {@code h2}. La de PostgreSQL se prueba en
     * {@code PostgresTitleSearchIntegrationTest}.
     */
    @Test
    void v5EstaAplicadaEnLaTablaDeHistorial() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '5' AND \"success\" = TRUE"
                        + " AND \"description\" = 'accent insensitive title search'",
                Integer.class);

        assertEquals(1, applied);
    }

    /**
     * Las dos funciones que usa {@code TitleSearch} existen con el mismo nombre
     * que en PostgreSQL. En H2 {@code streambox_word_similarity} devuelve 0 (sin
     * tolerancia a erratas) y {@code NULL} propaga {@code NULL}, como una función
     * {@code STRICT} de PostgreSQL.
     */
    @Test
    void lasFuncionesDeBusquedaExistenEnH2() {
        assertEquals("pokemon elite manana istanbul", jdbc.queryForObject(
                "SELECT streambox_unaccent(LOWER('Pokémon ÉLITE Mañana İstanbul'))", String.class));
        assertEquals("% _ \\", jdbc.queryForObject("SELECT streambox_unaccent('％ ＿ ＼')", String.class),
                "como unaccent, las formas de ancho completo pasan a ASCII");
        assertEquals(0.0, jdbc.queryForObject(
                "SELECT streambox_word_similarity('interstelar', 'interstellar')", Double.class));
        assertEquals(null, jdbc.queryForObject("SELECT streambox_unaccent(NULL)", String.class));
    }

    // --- V6: tráiler (trailer_url) y vídeo del contenido opcional ---

    /** Tráiler válido: la única forma que admite la base de datos (la normalizada). */
    private static final String TRAILER = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @Test
    void v6EstaAplicadaEnLaTablaDeHistorial() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '6' AND \"success\" = TRUE",
                Integer.class);

        assertEquals(1, applied);
    }

    /**
     * Las columnas nuevas existen con el tamaño de la entidad y admiten
     * {@code NULL}, y el vídeo de películas y episodios ha dejado de ser
     * obligatorio. El resto de URLs siguen siendo obligatorias.
     */
    @Test
    void lasColumnasDeTrailerYVideoTienenElTamanoYLaNulabilidadEsperados() {
        java.util.Map<String, String> nullable = new java.util.HashMap<>();
        java.util.Map<String, Integer> lengths = new java.util.HashMap<>();
        jdbc.queryForList("SELECT LOWER(TABLE_NAME) AS T, LOWER(COLUMN_NAME) AS C, IS_NULLABLE AS N,"
                        + " CHARACTER_MAXIMUM_LENGTH AS L FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE LOWER(COLUMN_NAME) IN ('trailer_url', 'video_url', 'image_url')")
                .forEach(r -> {
                    String key = r.get("T") + "." + r.get("C");
                    nullable.put(key, (String) r.get("N"));
                    lengths.put(key, ((Number) r.get("L")).intValue());
                });

        assertEquals("YES", nullable.get("movies.trailer_url"));
        assertEquals("YES", nullable.get("series.trailer_url"));
        assertEquals("YES", nullable.get("movies.video_url"));
        assertEquals("YES", nullable.get("episodes.video_url"));
        assertEquals("NO", nullable.get("movies.image_url"));
        assertEquals("NO", nullable.get("series.image_url"));
        assertEquals(null, nullable.get("episodes.trailer_url"), "los episodios no tienen tráiler propio");
        for (String column : List.of("movies.trailer_url", "series.trailer_url", "movies.video_url", "episodes.video_url")) {
            assertEquals(500, lengths.get(column), column);
        }
    }

    @Test
    void unaPeliculaYUnEpisodioPuedenGuardarseSinVideo() {
        long seriesId = insertSeries("Sin video", 2010, null);

        jdbc.update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                + " VALUES ('Sin video', 'd', 100, 2000, 'u', NULL, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, duration, video_url,"
                + " created_at) VALUES (?, 1, 1, 't', 30, NULL, CURRENT_TIMESTAMP)", seriesId);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM movies WHERE video_url IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM episodes WHERE video_url IS NULL", Integer.class));
    }

    /**
     * {@code ck_movies_trailer_url} y {@code ck_series_trailer_url}: el tráiler
     * es {@code NULL} o un vídeo de YouTube en la forma normalizada, con un id
     * de 11 caracteres de {@code [A-Za-z0-9_-]} (incluidos {@code -} y {@code _}).
     */
    @Test
    void elTrailerSoloAdmiteNuloOUnEnlaceDeYoutubeNormalizado() {
        long movieId = insertMovie("Trailer", 2000);
        long seriesId = insertSeries("Trailer", 2010, null);

        for (String valid : java.util.Arrays.asList(TRAILER, "https://www.youtube.com/watch?v=-PtU49Aol_0", null)) {
            jdbc.update("UPDATE movies SET trailer_url = ? WHERE id = ?", valid, movieId);
            jdbc.update("UPDATE series SET trailer_url = ? WHERE id = ?", valid, seriesId);
            assertEquals(valid, jdbc.queryForObject("SELECT trailer_url FROM movies WHERE id = ?", String.class, movieId));
            assertEquals(valid, jdbc.queryForObject("SELECT trailer_url FROM series WHERE id = ?", String.class, seriesId));
        }
        for (String invalid : TrailerUrlMigrationScenario.INVALID_TRAILERS) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE movies SET trailer_url = ? WHERE id = ?", invalid, movieId), invalid);
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE series SET trailer_url = ? WHERE id = ?", invalid, seriesId), invalid);
        }
    }

    // --- V7: icono de perfil (users.avatar) ---

    @Test
    void v7EstaAplicadaEnLaTablaDeHistorial() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '7' AND \"success\" = TRUE",
                Integer.class);

        assertEquals(1, applied);
    }

    /**
     * {@code users.avatar} es un {@code VARCHAR(32)} opcional: un usuario
     * creado sin avatar (como todos los anteriores a la {@code V7}) lo tiene a
     * {@code NULL} y la interfaz muestra su inicial.
     */
    @Test
    void elAvatarEsOpcionalYTieneLaLongitudDeLaEntidad() {
        java.util.Map<String, Object> column = jdbc.queryForMap("SELECT IS_NULLABLE AS N, CHARACTER_MAXIMUM_LENGTH AS L"
                + " FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE LOWER(TABLE_NAME) = 'users' AND LOWER(COLUMN_NAME) = 'avatar'");
        long userId = insertUser("sinavatar");

        assertEquals("YES", column.get("N"));
        assertEquals(32, ((Number) column.get("L")).intValue());
        assertEquals(null, jdbc.queryForObject("SELECT avatar FROM users WHERE id = ?", String.class, userId));
    }

    /**
     * {@code ck_users_avatar}: el avatar es {@code NULL} o una clave de 1 a 32
     * caracteres de {@code [a-z0-9-]}. No comprueba la lista de avatares (eso
     * lo hace el backend), solo el formato. Un texto de más de 32 caracteres
     * lo rechaza el propio {@code VARCHAR(32)}.
     */
    @Test
    void elAvatarSoloAdmiteNuloOUnaClaveDeMinusculasDigitosYGuiones() {
        long userId = insertUser("avatar");

        for (String valid : UserAvatarSchemaCases.VALID) {
            jdbc.update("UPDATE users SET avatar = ? WHERE id = ?", valid, userId);
            assertEquals(valid, jdbc.queryForObject("SELECT avatar FROM users WHERE id = ?", String.class, userId));
        }
        jdbc.update("UPDATE users SET avatar = NULL WHERE id = ?", userId);
        for (String invalid : UserAvatarSchemaCases.INVALID_FORMAT) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE users SET avatar = ? WHERE id = ?", invalid, userId), invalid);
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE users SET avatar = ? WHERE id = ?", "a".repeat(33), userId));
        assertEquals(null, jdbc.queryForObject("SELECT avatar FROM users WHERE id = ?", String.class, userId));
    }

    // --- V8: «me gusta» (user_liked_movies, user_liked_series) ---

    @Test
    void v8EstaAplicadaEnLaTablaDeHistorial() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '8' AND \"success\" = TRUE",
                Integer.class);

        assertEquals(1, applied);
    }

    /**
     * Las dos tablas tienen la clave primaria compuesta {@code (user_id,
     * <título>_id)} y un {@code created_at} con zona horaria y obligatorio (el
     * «cuándo» que ordena la sección «Te gusta»).
     */
    @Test
    void lasTablasDeMeGustaTienenSuClavePrimariaYUnaFechaObligatoriaConZona() {
        assertEquals(List.of("user_id", "movie_id"), keyColumns("pk_user_liked_movies"));
        assertEquals(List.of("user_id", "series_id"), keyColumns("pk_user_liked_series"));
        for (String table : List.of("user_liked_movies", "user_liked_series")) {
            java.util.Map<String, Object> column = jdbc.queryForMap("SELECT DATA_TYPE AS T, IS_NULLABLE AS N"
                    + " FROM INFORMATION_SCHEMA.COLUMNS"
                    + " WHERE LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = 'created_at'", table);
            assertEquals("TIMESTAMP WITH TIME ZONE", column.get("T"), table);
            assertEquals("NO", column.get("N"), table);
        }
    }

    /**
     * Índices de la V8 con sus columnas y su orden: por título (cascada al
     * borrarlo) y {@code (user_id, created_at DESC)} (sección «Te gusta», del
     * más reciente al más antiguo).
     */
    @Test
    void existenLosIndicesDeMeGustaConSusColumnasYOrden() {
        assertEquals(List.of("movie_id ASC"), indexColumns("idx_liked_movies_movie_id"));
        assertEquals(List.of("series_id ASC"), indexColumns("idx_liked_series_series_id"));
        assertEquals(List.of("user_id ASC", "created_at DESC"), indexColumns("idx_liked_movies_user_created_at"));
        assertEquals(List.of("user_id ASC", "created_at DESC"), indexColumns("idx_liked_series_user_created_at"));
    }

    /** Las cuatro claves foráneas de la V8 borran en cascada (decisión del autor). */
    @Test
    void lasClavesForaneasDeMeGustaBorranEnCascada() {
        java.util.Map<String, String> rules = new java.util.HashMap<>();
        jdbc.queryForList("SELECT LOWER(CONSTRAINT_NAME) AS NAME, DELETE_RULE AS RULE"
                        + " FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS")
                .forEach(r -> rules.put((String) r.get("NAME"), (String) r.get("RULE")));

        assertEquals("CASCADE", rules.get("fk_liked_movies_user"));
        assertEquals("CASCADE", rules.get("fk_liked_movies_movie"));
        assertEquals("CASCADE", rules.get("fk_liked_series_user"));
        assertEquals("CASCADE", rules.get("fk_liked_series_series"));
    }

    /**
     * Borrar una película o una serie borra solo sus «me gusta» (no los de otros
     * títulos ni al usuario); borrar un usuario borra todos los suyos y ninguno
     * de los de otro usuario.
     */
    @Test
    void borrarUnTituloOUnUsuarioBorraSusMeGustaYNadaMas() {
        long kept = insertMovie("Sigue gustando", 2000);
        long doomed = insertMovie("Se borra", 2000);
        long keptSeries = insertSeries("Serie que sigue", 2010, null);
        long doomedSeries = insertSeries("Serie que se borra", 2010, null);
        long ana = insertUser("likeana");
        long bea = insertUser("likebea");
        for (long user : new long[] { ana, bea }) {
            insertLike("user_liked_movies", "movie_id", user, kept);
            insertLike("user_liked_movies", "movie_id", user, doomed);
            insertLike("user_liked_series", "series_id", user, keptSeries);
            insertLike("user_liked_series", "series_id", user, doomedSeries);
        }

        jdbc.update("DELETE FROM movies WHERE id = ?", doomed);
        jdbc.update("DELETE FROM series WHERE id = ?", doomedSeries);

        assertEquals(0, countWhere("user_liked_movies", "movie_id", doomed));
        assertEquals(2, countWhere("user_liked_movies", "movie_id", kept));
        assertEquals(0, countWhere("user_liked_series", "series_id", doomedSeries));
        assertEquals(2, countWhere("user_liked_series", "series_id", keptSeries));

        jdbc.update("DELETE FROM users WHERE id = ?", ana);

        assertEquals(0, countWhere("user_liked_movies", "user_id", ana));
        assertEquals(0, countWhere("user_liked_series", "user_id", ana));
        assertEquals(1, countWhere("user_liked_movies", "user_id", bea));
        assertEquals(1, countWhere("user_liked_series", "user_id", bea));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM movies WHERE id = ?", Integer.class, kept));
    }

    /**
     * La clave primaria impide dos «me gusta» del mismo usuario al mismo título
     * (un {@code INSERT} normal falla; el repositorio usa {@code ON CONFLICT DO
     * NOTHING} para que repetirlo no sea un error), y las claves foráneas
     * impiden un «me gusta» de un título o un usuario inexistentes. Tampoco
     * admite una fecha nula.
     */
    @Test
    void laBaseDeDatosRechazaMeGustaDuplicadosHuerfanosOSinFecha() {
        long movieId = insertMovie("Una vez", 2000);
        long seriesId = insertSeries("Una vez serie", 2010, null);
        long userId = insertUser("likedup");
        insertLike("user_liked_movies", "movie_id", userId, movieId);
        insertLike("user_liked_series", "series_id", userId, seriesId);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertLike("user_liked_movies", "movie_id", userId, movieId));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertLike("user_liked_series", "series_id", userId, seriesId));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertLike("user_liked_movies", "movie_id", userId, Long.MAX_VALUE));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertLike("user_liked_series", "series_id", Long.MAX_VALUE, seriesId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO user_liked_movies (user_id, movie_id, created_at) VALUES (?, ?, NULL)",
                userId, insertMovie("Sin fecha", 2000)));
    }

    // --- Utilidades ---

    private void insertLike(String table, String titleColumn, long userId, long titleId) {
        jdbc.update("INSERT INTO " + table + " (user_id, " + titleColumn + ", created_at)"
                + " VALUES (?, ?, CURRENT_TIMESTAMP)", userId, titleId);
    }

    private int countWhere(String table, String column, long value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    /** Columnas de una restricción (clave primaria), en su orden. */
    private List<String> keyColumns(String constraint) {
        return jdbc.queryForList("SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE"
                + " WHERE LOWER(CONSTRAINT_NAME) = ? ORDER BY ORDINAL_POSITION", String.class, constraint);
    }

    /** Columnas de un índice con su sentido ({@code ASC}/{@code DESC}), en su orden. */
    private List<String> indexColumns(String index) {
        return jdbc.queryForList("SELECT LOWER(COLUMN_NAME) || ' ' || ORDERING_SPECIFICATION"
                + " FROM INFORMATION_SCHEMA.INDEX_COLUMNS"
                + " WHERE LOWER(INDEX_NAME) = ? ORDER BY ORDINAL_POSITION", String.class, index);
    }

    /** Token válido de un día (familia de siete) con el sucesor indicado. */
    private long insertToken(long userId, String hash, java.util.UUID family, Long replacedById) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, expires_at,"
                + " family_expires_at, replaced_by_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, hash, family, now, now.plusDays(1), now.plusDays(7), replacedById);
        return jdbc.queryForObject("SELECT id FROM refresh_tokens WHERE token_hash = ?", Long.class, hash);
    }

    private void insertToken(long userId, String hash, java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime expiresAt, java.time.OffsetDateTime familyExpiresAt) {
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at, expires_at,"
                + " family_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, hash, java.util.UUID.randomUUID(), createdAt, expiresAt, familyExpiresAt);
    }

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
