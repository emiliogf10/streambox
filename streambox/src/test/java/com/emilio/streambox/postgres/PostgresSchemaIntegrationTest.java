package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.migration.UserAvatarSchemaCases;

import jakarta.persistence.EntityManagerFactory;

/**
 * Esquema que producen las migraciones de Flyway sobre un <b>PostgreSQL real</b>
 * (equivalente a {@code FlywaySchemaIntegrationTest}, que usa H2).
 *
 * <p>
 * Que el contexto de Spring arranque ya demuestra dos cosas: que todas las
 * migraciones ({@code V1}..{@code V16}) se aplican en PostgreSQL desde cero y que Hibernate
 * ({@code ddl-auto=validate}) acepta las entidades contra ese esquema. Aquí se
 * comprueba además lo que H2 no puede garantizar: los tipos reales del catálogo
 * del sistema ({@code information_schema}, {@code pg_catalog}), las políticas de
 * borrado de las claves foráneas y que cada restricción la rechaza la propia
 * base de datos con su {@code SQLSTATE} real.
 * </p>
 *
 * <p>
 * Los datos se confirman de verdad (sin {@code @Transactional}) y se limpian
 * antes y después de cada test, porque el contenedor es compartido.
 * </p>
 */
class PostgresSchemaIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Sin TRUNCATE: borrado normal, hijas primero, para no depender de nada más.
        // Las tablas de series (V3) van antes porque series_genres referencia a genres.
        jdbc.update("DELETE FROM user_favorite_series");
        jdbc.update("DELETE FROM episodes");
        jdbc.update("DELETE FROM series_genres");
        jdbc.update("DELETE FROM series");
        jdbc.update("DELETE FROM user_favorite_movies");
        jdbc.update("DELETE FROM movie_genres");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM movies");
        jdbc.update("DELETE FROM genres");
        jdbc.update("DELETE FROM username_reservations"); // V13: sus dueños se van en cascada
    }

    // ------------------------------------------------------------------
    // Flyway y Hibernate
    // ------------------------------------------------------------------

    /**
     * En una base de datos vacía Flyway aplica exactamente V1, V2, V3, V4, la V5 de PostgreSQL, V6, V7, V8, V9, V10, V11, V12, V13, V14, V15 y V16 (sin
     * fila de baseline: esa solo aparece al adoptar una base existente) y todas
     * terminan bien. Protege contra una migración nueva que falle en PostgreSQL
     * pero no en H2. Al añadir una migración, hay que añadir su versión aquí.
     */
    @Test
    void flywayAplicaTodasLasMigracionesDesdeCeroSinBaseline() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");

        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16"), history.stream().map(r -> (String) r.get("version")).toList(),
                "historial inesperado: " + history);
        assertTrue(history.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success"))));
        assertTrue(history.stream().allMatch(r -> "SQL".equals(r.get("type"))));
    }

    /**
     * Si alguien cambiara {@code ddl-auto} a {@code update} o {@code create},
     * Hibernate "arreglaría" el esquema por su cuenta y los demás tests de esta
     * clase dejarían de probar las migraciones. Se comprueba que valida.
     */
    @Test
    void hibernateSoloValidaElEsquema() {
        Object mode = entityManagerFactory.unwrap(SessionFactory.class)
                .getProperties().get("hibernate.hbm2ddl.auto");

        assertEquals("validate", String.valueOf(mode));
    }

    // ------------------------------------------------------------------
    // Tipos reales de columna
    // ------------------------------------------------------------------

    /**
     * Tras {@code V2} las fechas de creación son {@code timestamp with time
     * zone} (en V1 eran {@code timestamp} sin zona). H2 acepta el
     * {@code ALTER COLUMN ... SET DATA TYPE} pero solo PostgreSQL confirma el
     * tipo final tal como lo ve un cliente.
     */
    @Test
    void createdAtEsTimestampConZonaHorariaEnUsuariosYPeliculas() {
        for (String table : List.of("users", "movies")) {
            String type = jdbc.queryForObject(
                    "SELECT data_type FROM information_schema.columns"
                            + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'created_at'",
                    String.class, table);
            assertEquals("timestamp with time zone", type, table + ".created_at");
        }
    }

    /**
     * Longitudes de los {@code VARCHAR} y obligatoriedad de cada columna: solo
     * admiten {@code NULL} el vídeo y el tráiler de la película (opcionales
     * desde la {@code V6}), sus subtítulos ({@code V15}), su imagen de cabecera
     * ({@code V16}), el avatar del usuario ({@code V7}; {@code NULL} =
     * se muestra la inicial), {@code users.email_verified_at} ({@code V9};
     * {@code NULL} = cuenta pendiente de verificar el correo) y
     * {@code users.genres_onboarded_at} ({@code V11}; {@code NULL} = aún no se
     * le ha hecho la pregunta de sus géneros favoritos).
     */
    @Test
    void lasLongitudesYLaObligatoriedadDeLasColumnasSonLasDeLasEntidades() {
        Map<String, Integer> expectedLengths = Map.ofEntries(
                Map.entry("users.username", 50), Map.entry("users.email", 100),
                Map.entry("users.password", 255), Map.entry("users.role", 20), Map.entry("users.avatar", 32),
                Map.entry("genres.name", 50),
                Map.entry("movies.title", 150), Map.entry("movies.description", 1000),
                Map.entry("movies.image_url", 500), Map.entry("movies.video_url", 500),
                Map.entry("movies.trailer_url", 500), Map.entry("movies.subtitles_url", 500),
                Map.entry("movies.backdrop_url", 500));

        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT table_name, column_name, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name IN ('users','genres','movies','movie_genres','user_favorite_movies')");

        for (Map.Entry<String, Integer> expected : expectedLengths.entrySet()) {
            Map<String, Object> column = columns.stream()
                    .filter(c -> (c.get("table_name") + "." + c.get("column_name")).equals(expected.getKey()))
                    .findFirst().orElseThrow(() -> new AssertionError("falta la columna " + expected.getKey()));
            assertEquals(expected.getValue(), column.get("character_maximum_length"), expected.getKey());
        }
        // Solo el vídeo, el tráiler, los subtítulos y la imagen de cabecera de la película, el avatar del usuario, su
        // fecha de verificación del correo (V9: NULL = cuenta pendiente) y la de
        // la pregunta de bienvenida (V11: NULL = aún no se le ha hecho) admiten NULL
        assertEquals(java.util.Set.of("movies.video_url", "movies.trailer_url", "movies.subtitles_url",
                "movies.backdrop_url", "users.avatar",
                "users.email_verified_at", "users.genres_onboarded_at"), java.util.Set.copyOf(columns.stream()
                .filter(c -> !"NO".equals(c.get("is_nullable")))
                .map(c -> c.get("table_name") + "." + c.get("column_name"))
                .toList()));
    }

    /**
     * Los identificadores son {@code BIGINT GENERATED BY DEFAULT AS IDENTITY}
     * (lo que Hibernate espera con {@code GenerationType.IDENTITY}), no una
     * {@code bigserial} ni una columna sin generador.
     */
    @Test
    void losIdentificadoresSonBigintIdentity() {
        for (String table : List.of("users", "genres", "movies")) {
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

    /** Las dos tablas de unión tienen clave primaria compuesta, en este orden. */
    @Test
    void lasTablasDeUnionTienenClavePrimariaCompuesta() {
        assertEquals(List.of("movie_id", "genre_id"), primaryKeyColumns("movie_genres"));
        assertEquals(List.of("user_id", "movie_id"), primaryKeyColumns("user_favorite_movies"));
        assertEquals(List.of("id"), primaryKeyColumns("users"));
        assertEquals(List.of("id"), primaryKeyColumns("movies"));
        assertEquals(List.of("id"), primaryKeyColumns("genres"));
    }

    /**
     * Existen los índices que cubren las búsquedas por la segunda columna de las
     * claves compuestas y por año, cada uno sobre la columna correcta (se lee la
     * definición real con {@code pg_indexes}, no solo el nombre).
     */
    @Test
    void existenLosIndicesDelCatalogoSobreLasColumnasCorrectas() {
        Map<String, String> definitions = new java.util.HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));

        assertTrue(definitions.get("idx_movie_genres_genre_id").contains("ON public.movie_genres USING btree (genre_id)"),
                definitions.get("idx_movie_genres_genre_id"));
        assertTrue(definitions.get("idx_favorites_movie_id").contains("ON public.user_favorite_movies USING btree (movie_id)"),
                definitions.get("idx_favorites_movie_id"));
        assertTrue(definitions.get("idx_movies_release_year").contains("ON public.movies USING btree (release_year)"),
                definitions.get("idx_movies_release_year"));
    }

    /**
     * Unicidad de usuario, email y nombre de género, por restricción con nombre
     * (más la de episodios de V3, que se prueba en
     * {@code PostgresSeriesSchemaIntegrationTest}, y la del hash de los refresh
     * tokens de V4, en {@code PostgresRefreshTokenIntegrationTest}, y la de
     * los tokens de cuenta de V9, en {@code PostgresAccountTokenIntegrationTest}).
     */
    @Test
    void existenLasRestriccionesDeUnicidadYSeRechazanLosDuplicadosConElCodigoReal() {
        Set<String> uniques = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'u' AND connamespace = 'public'::regnamespace",
                String.class));
        assertEquals(Set.of("uk_users_username", "uk_users_email", "uk_genres_name",
                "uk_episodes_series_season_episode", "uk_refresh_tokens_token_hash",
                "uk_account_tokens_token_hash"), uniques);

        insertUser("dup", "dup@test.com");
        DataIntegrityViolationException byUsername = assertThrows(DataIntegrityViolationException.class,
                () -> insertUser("dup", "otro@test.com"));
        DataIntegrityViolationException byEmail = assertThrows(DataIntegrityViolationException.class,
                () -> insertUser("otro", "dup@test.com"));

        assertEquals(UNIQUE_VIOLATION, sqlState(byUsername));
        assertTrue(byUsername.getMessage().contains("uk_users_username"), byUsername.getMessage());
        assertEquals(UNIQUE_VIOLATION, sqlState(byEmail));
        assertTrue(byEmail.getMessage().contains("uk_users_email"), byEmail.getMessage());

        insertGenre("Drama");
        assertEquals(UNIQUE_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> insertGenre("Drama"))));
    }

    /**
     * El unique de PostgreSQL distingue mayúsculas: "Drama" y "drama" son
     * géneros distintos para la base de datos. Lo evita (si procede) la capa de
     * servicio, no la restricción. Se documenta como diferencia respecto a lo
     * que cabría suponer de un campo "nombre".
     */
    @Test
    void laUnicidadDeNombresDeGeneroDistingueMayusculasEnLaBaseDeDatos() {
        insertGenre("Drama");

        insertGenre("drama"); // no lanza: son valores distintos para PostgreSQL

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM genres", Integer.class));
    }

    /** Las claves foráneas con la política de borrado que declara V1. */
    @Test
    void lasClavesForaneasTienenLaPoliticaDeBorradoEsperada() {
        Map<String, String> rules = new java.util.HashMap<>();
        jdbc.queryForList("SELECT constraint_name, delete_rule FROM information_schema.referential_constraints"
                        + " WHERE constraint_schema = 'public'")
                .forEach(r -> rules.put((String) r.get("constraint_name"), (String) r.get("delete_rule")));

        assertEquals("CASCADE", rules.get("fk_movie_genres_movie"));
        assertEquals("NO ACTION", rules.get("fk_movie_genres_genre"), "un género en uso no se puede borrar");
        assertEquals("CASCADE", rules.get("fk_favorites_user"));
        assertEquals("CASCADE", rules.get("fk_favorites_movie"));
    }

    /** Borrar una película arrastra sus géneros y favoritos, pero no usuarios ni géneros. */
    @Test
    void borrarUnaPeliculaBorraSusFavoritosYGenerosEnCascada() {
        long movieId = insertMovie("Cascade", 2000);
        long userId = insertUser("cascade", "cascade@test.com");
        long genreId = insertGenre("Cascadegenre");
        jdbc.update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);
        jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", userId, movieId);

        jdbc.update("DELETE FROM movies WHERE id = ?", movieId);

        assertEquals(0, count("user_favorite_movies"));
        assertEquals(0, count("movie_genres"));
        assertEquals(1, count("users"));
        assertEquals(1, count("genres"));
    }

    /** Borrar un usuario arrastra sus favoritos, pero no la película. */
    @Test
    void borrarUnUsuarioBorraSusFavoritosPeroNoLaPelicula() {
        long movieId = insertMovie("Keep", 2000);
        long userId = insertUser("gone", "gone@test.com");
        jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", userId, movieId);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertEquals(0, count("user_favorite_movies"));
        assertEquals(1, count("movies"));
    }

    /** Un género en uso no se puede borrar: error real de clave foránea (23503). */
    @Test
    void noSePuedeBorrarUnGeneroQueUnaPeliculaUsa() {
        long movieId = insertMovie("InUse", 2000);
        long genreId = insertGenre("Inuse");
        jdbc.update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM genres WHERE id = ?", genreId));

        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(error));
        assertTrue(error.getMessage().contains("fk_movie_genres_genre"), error.getMessage());
    }

    /** No se puede insertar una pareja de unión que apunta a una fila inexistente. */
    @Test
    void lasTablasDeUnionRechazanReferenciasAFilasInexistentes() {
        long userId = insertUser("ref", "ref@test.com");

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", userId, 987654L));

        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(error));
    }

    /** Las claves primarias compuestas impiden el duplicado de una pareja (23505). */
    @Test
    void lasClavesPrimariasCompuestasRechazanParejasDuplicadas() {
        long movieId = insertMovie("Pk", 2000);
        long userId = insertUser("pk", "pk@test.com");
        jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", userId, movieId);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", userId, movieId));

        assertEquals(UNIQUE_VIOLATION, sqlState(error));
        assertTrue(error.getMessage().contains("user_favorite_movies_pkey"), error.getMessage());
    }

    /** Existen las restricciones CHECK de usuarios y películas con su nombre. */
    @Test
    void existenLasRestriccionesCheckConNombre() {
        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
                String.class));

        assertTrue(checks.containsAll(Set.of("ck_users_role", "ck_movies_duration", "ck_movies_release_year",
                "ck_users_avatar", "ck_users_token_version")),
                "checks: " + checks);
    }

    /**
     * {@code users.token_version} (V12) en PostgreSQL: {@code integer NOT NULL
     * DEFAULT 0}, una cuenta creada sin indicarla nace en 0, y la base de datos
     * rechaza {@code NULL} (23502) y una versión negativa (23514, con el nombre
     * {@code ck_users_token_version}).
     */
    @Test
    void laVersionDeLosTokensEsUnEnteroObligatorioQueEmpiezaEnCeroYNuncaEsNegativo() {
        Map<String, Object> column = jdbc.queryForMap("SELECT data_type, is_nullable, column_default"
                + " FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = 'users' AND column_name = 'token_version'");
        long userId = insertUser("version", "version@test.com");

        assertEquals("integer", column.get("data_type"));
        assertEquals("NO", column.get("is_nullable"));
        assertEquals("0", column.get("column_default"));
        assertEquals(0, jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, userId));

        DataIntegrityViolationException negative = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE users SET token_version = -1 WHERE id = ?", userId));
        assertEquals(CHECK_VIOLATION, sqlState(negative));
        assertTrue(negative.getMessage().contains("ck_users_token_version"), negative.getMessage());
        DataIntegrityViolationException nulled = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE users SET token_version = NULL WHERE id = ?", userId));
        assertEquals(NOT_NULL_VIOLATION, sqlState(nulled));
        assertEquals(0, jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, userId));
    }

    /** PostgreSQL rechaza años y duraciones imposibles con 23514 y el nombre del CHECK. */
    @Test
    void laBaseDeDatosRechazaAnosYDuracionesImposibles() {
        for (int year : new int[] { 1887, 1700, 2101 }) {
            DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                    () -> insertMovie("Anio " + year, year));
            assertEquals(CHECK_VIOLATION, sqlState(error), "año " + year);
            assertTrue(error.getMessage().contains("ck_movies_release_year"), error.getMessage());
        }
        for (int duration : new int[] { 0, -5 }) {
            DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                    () -> insertMovie("Duracion " + duration, 2000, duration));
            assertEquals(CHECK_VIOLATION, sqlState(error), "duración " + duration);
            assertTrue(error.getMessage().contains("ck_movies_duration"), error.getMessage());
        }
        assertEquals(0, count("movies"));
    }

    /** Los extremos válidos del CHECK de año (1888 y 2100) y la duración mínima (1) se aceptan. */
    @Test
    void losValoresLimiteDeLosCheckSeAceptan() {
        insertMovie("Primera", 1888);
        insertMovie("Futura", 2100);
        insertMovie("Cortisima", 2000, 1);

        assertEquals(3, count("movies"));
    }

    /** Un rol desconocido lo rechaza el CHECK de la propia base de datos. */
    @Test
    void laBaseDeDatosRechazaRolesDesconocidos() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO users (username, email, password, role, created_at)"
                        + " VALUES ('x', 'x@test.com', 'p', 'SUPERADMIN', CURRENT_TIMESTAMP)"));

        assertEquals(CHECK_VIOLATION, sqlState(error));
        assertTrue(error.getMessage().contains("ck_users_role"), error.getMessage());
    }

    /**
     * {@code ck_users_avatar} (V7) en PostgreSQL: rechaza con 23514 las mismas
     * cadenas que en H2 ({@link UserAvatarSchemaCases#INVALID_FORMAT}) y acepta
     * las claves con formato válido y {@code NULL}. Importa probarlo aquí porque
     * {@code TRANSLATE}, {@code REPEAT} y {@code CHAR_LENGTH} se comportan con
     * texto Unicode según el motor. Más de 32 caracteres lo rechaza el
     * {@code VARCHAR(32)} (22001).
     */
    @Test
    void laBaseDeDatosSoloAdmiteAvataresConFormatoDeClave() {
        long userId = insertUser("avatar", "avatar@test.com");

        for (String valid : UserAvatarSchemaCases.VALID) {
            jdbc.update("UPDATE users SET avatar = ? WHERE id = ?", valid, userId);
            assertEquals(valid, jdbc.queryForObject("SELECT avatar FROM users WHERE id = ?", String.class, userId));
        }
        jdbc.update("UPDATE users SET avatar = NULL WHERE id = ?", userId);
        for (String invalid : UserAvatarSchemaCases.INVALID_FORMAT) {
            DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE users SET avatar = ? WHERE id = ?", invalid, userId), invalid);
            assertEquals(CHECK_VIOLATION, sqlState(error), invalid);
            assertTrue(error.getMessage().contains("ck_users_avatar"), error.getMessage());
        }
        DataIntegrityViolationException tooLong = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE users SET avatar = ? WHERE id = ?", "a".repeat(33), userId));
        assertEquals("22001", sqlState(tooLong));
        assertEquals(null, jdbc.queryForObject("SELECT avatar FROM users WHERE id = ?", String.class, userId));
    }

    /** Los {@code NOT NULL} se aplican en la base de datos (23502), no solo en Java. */
    @Test
    void laBaseDeDatosRechazaNulosEnColumnasObligatorias() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO genres (name) VALUES (NULL)"));

        assertEquals(NOT_NULL_VIOLATION, sqlState(error));
    }

    /**
     * PostgreSQL, a diferencia de H2, rechaza un texto más largo que el
     * {@code VARCHAR} (22001) en lugar de truncarlo o ampliarlo. Confirma que
     * los límites del esquema se aplican de verdad.
     */
    @Test
    void laBaseDeDatosRechazaTextosMasLargosQueLaColumna() {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, () ->
                insertMovie("x".repeat(151), 2000));

        assertEquals("22001", sqlState(error));
    }

    // ------------------------------------------------------------------
    // Fechas
    // ------------------------------------------------------------------

    /**
     * Un {@code Instant} guardado por Hibernate en una columna
     * {@code timestamptz} se lee como el mismo instante: PostgreSQL guarda el
     * instante absoluto (epoch) y {@code hibernate.jdbc.time_zone=UTC} evita que
     * la zona del servidor lo desplace. Se compara el epoch que ve la base de
     * datos con el de la entidad.
     */
    @Test
    void elInstanteDeCreacionSeGuardaSinDesplazamientoDeZona() {
        Genre drama = saveGenre("Drama");
        Instant before = Instant.now();
        Movie movie = saveMovie("Fecha", 2000, drama);
        Instant after = Instant.now();

        double epoch = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM created_at) FROM movies WHERE id = ?", Double.class, movie.getId());
        Instant stored = Instant.ofEpochMilli((long) (epoch * 1000));

        assertTrue(!stored.isBefore(before.minusMillis(5)) && !stored.isAfter(after.plusMillis(5)),
                "instante guardado " + stored + " fuera de [" + before + ", " + after + "]");
        assertTrue(Duration.between(movie.getCreatedAt(), stored).abs().toMillis() <= 1,
                "la entidad (" + movie.getCreatedAt() + ") y la BD (" + stored + ") difieren");
    }

    // ------------------------------------------------------------------
    // Idempotencia de V1
    // ------------------------------------------------------------------

    /**
     * {@code V1} es idempotente ({@code IF NOT EXISTS}): volver a ejecutar su
     * script sobre una base ya migrada y con datos no falla ni destruye nada.
     * Es la propiedad en la que se apoya la adopción de bases existentes.
     */
    @Test
    void ejecutarDeNuevoV1SobreUnaBaseConDatosNoFallaNiPierdeFilas() {
        insertMovie("Persistente", 2000);
        insertUser("persistente", "persistente@test.com");

        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_schema.sql"))
                .execute(dataSource);

        assertEquals(1, count("movies"));
        assertEquals(1, count("users"));
    }

    // ------------------------------------------------------------------
    // V8: «me gusta» (user_liked_movies, user_liked_series)
    // ------------------------------------------------------------------

    /**
     * Tablas de la V8 tal como las ve PostgreSQL: clave primaria compuesta
     * {@code (user_id, <título>_id)}, {@code created_at} {@code timestamptz}
     * obligatorio, las cuatro claves foráneas en cascada y los índices con sus
     * columnas y su sentido reales ({@code pg_indexes}). Sus consultas (y el
     * borrado en cascada de punta a punta) se prueban en
     * {@code PostgresLikesIntegrationTest}.
     */
    @Test
    void lasTablasDeMeGustaTienenClavesCascadaEIndicesEsperados() {
        assertEquals(List.of("user_id", "movie_id"), primaryKeyColumns("user_liked_movies"));
        assertEquals(List.of("user_id", "series_id"), primaryKeyColumns("user_liked_series"));
        for (String table : List.of("user_liked_movies", "user_liked_series")) {
            Map<String, Object> column = jdbc.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'created_at'", table);
            assertEquals("timestamp with time zone", column.get("data_type"), table);
            assertEquals("NO", column.get("is_nullable"), table);
        }

        Map<String, String> rules = new java.util.HashMap<>();
        jdbc.queryForList("SELECT constraint_name, delete_rule FROM information_schema.referential_constraints"
                        + " WHERE constraint_schema = 'public'")
                .forEach(r -> rules.put((String) r.get("constraint_name"), (String) r.get("delete_rule")));
        for (String fk : List.of("fk_liked_movies_user", "fk_liked_movies_movie",
                "fk_liked_series_user", "fk_liked_series_series")) {
            assertEquals("CASCADE", rules.get(fk), fk);
        }

        Map<String, String> definitions = new java.util.HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));
        assertIndex(definitions, "idx_liked_movies_movie_id", "ON public.user_liked_movies USING btree (movie_id)");
        assertIndex(definitions, "idx_liked_series_series_id", "ON public.user_liked_series USING btree (series_id)");
        assertIndex(definitions, "idx_liked_movies_user_created_at",
                "ON public.user_liked_movies USING btree (user_id, created_at DESC)");
        assertIndex(definitions, "idx_liked_series_user_created_at",
                "ON public.user_liked_series USING btree (user_id, created_at DESC)");
    }

    /**
     * Con el código real de PostgreSQL: un «me gusta» repetido viola la clave
     * primaria ({@code 23505}) con un {@code INSERT} normal y no hace nada con
     * {@code ON CONFLICT DO NOTHING} (lo que usa el repositorio); uno hacia una
     * película inexistente viola la clave foránea ({@code 23503}) incluso con
     * {@code ON CONFLICT}; y la fecha es obligatoria ({@code 23502}).
     */
    @Test
    void laBaseDeDatosRechazaMeGustaDuplicadosHuerfanosOSinFechaConElCodigoReal() {
        long movieId = insertMovie("Gusta", 2000);
        long userId = insertUser("gusta", "gusta@test.com");
        String insert = "INSERT INTO user_liked_movies (user_id, movie_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)";
        jdbc.update(insert, userId, movieId);

        assertEquals(UNIQUE_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, userId, movieId))));
        assertEquals(0, jdbc.update(insert + " ON CONFLICT DO NOTHING", userId, movieId));
        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert + " ON CONFLICT DO NOTHING", userId, Long.MAX_VALUE))));
        assertEquals(NOT_NULL_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO user_liked_movies (user_id, movie_id, created_at) VALUES (?, ?, NULL)",
                        userId, insertMovie("Sin fecha", 2000)))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_liked_movies", Integer.class));
    }

    // ------------------------------------------------------------------
    // V11: géneros favoritos (user_favorite_genres, users.genres_onboarded_at)
    // ------------------------------------------------------------------

    /**
     * Esquema de la V11 tal como lo ve PostgreSQL: clave primaria
     * {@code (user_id, genre_id)}, {@code created_at} {@code timestamptz}
     * obligatorio, las dos claves foráneas en cascada, el índice por
     * {@code genre_id} y {@code users.genres_onboarded_at} opcional y
     * <b>sin valor por defecto</b> (una cuenta nueva nace con la pregunta
     * pendiente). Sus consultas se prueban en
     * {@code PostgresFavoriteGenresIntegrationTest} y el relleno de las cuentas
     * existentes en {@code PostgresFavoriteGenresMigrationIntegrationTest}.
     */
    @Test
    void laTablaDeGenerosFavoritosYLaColumnaDeBienvenidaTienenElEsquemaEsperado() {
        assertEquals(List.of("user_id", "genre_id"), primaryKeyColumns("user_favorite_genres"));
        Map<String, Object> createdAt = jdbc.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'user_favorite_genres' AND column_name = 'created_at'");
        assertEquals("timestamp with time zone", createdAt.get("data_type"));
        assertEquals("NO", createdAt.get("is_nullable"));

        Map<String, Object> onboarded = jdbc.queryForMap("SELECT data_type, is_nullable, column_default"
                + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users'"
                + " AND column_name = 'genres_onboarded_at'");
        assertEquals("timestamp with time zone", onboarded.get("data_type"));
        assertEquals("YES", onboarded.get("is_nullable"));
        assertEquals(null, onboarded.get("column_default"), "sin DEFAULT: cerraría la pregunta de las cuentas nuevas");

        Map<String, String> rules = new java.util.HashMap<>();
        jdbc.queryForList("SELECT constraint_name, delete_rule FROM information_schema.referential_constraints"
                        + " WHERE constraint_schema = 'public'")
                .forEach(r -> rules.put((String) r.get("constraint_name"), (String) r.get("delete_rule")));
        assertEquals("CASCADE", rules.get("fk_favorite_genres_user"));
        assertEquals("CASCADE", rules.get("fk_favorite_genres_genre"));

        Map<String, String> definitions = new java.util.HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));
        assertIndex(definitions, "idx_favorite_genres_genre_id",
                "ON public.user_favorite_genres USING btree (genre_id)");

        long userId = insertUser("recien", "recien@test.com");
        assertEquals(null, jdbc.queryForObject("SELECT genres_onboarded_at FROM users WHERE id = ?",
                java.time.OffsetDateTime.class, userId), "una cuenta nueva nace con la pregunta pendiente");
    }

    /**
     * Con el código real de PostgreSQL: un género repetido viola la clave
     * primaria ({@code 23505}), un género o un usuario inexistentes la clave
     * foránea ({@code 23503}) y la fecha es obligatoria ({@code 23502}).
     * Borrar un género que solo está en los gustos <b>no falla</b> (a
     * diferencia de uno que usa una película) y se lleva sus filas.
     */
    @Test
    void laBaseDeDatosRechazaGustosDuplicadosHuerfanosOSinFechaYBorrarElGeneroLosArrastra() {
        long userId = insertUser("gustos", "gustos@test.com");
        long drama = insertGenre("Drama");
        long comedy = insertGenre("Comedia");
        String insert = "INSERT INTO user_favorite_genres (user_id, genre_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)";
        jdbc.update(insert, userId, drama);
        jdbc.update(insert, userId, comedy);

        assertEquals(UNIQUE_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, userId, drama))));
        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, userId, Long.MAX_VALUE))));
        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, Long.MAX_VALUE, drama))));
        assertEquals(NOT_NULL_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO user_favorite_genres (user_id, genre_id, created_at) VALUES (?, ?, NULL)",
                        userId, insertGenre("Sin fecha")))));

        jdbc.update("DELETE FROM genres WHERE id = ?", drama);

        assertEquals(List.of(comedy), jdbc.queryForList(
                "SELECT genre_id FROM user_favorite_genres WHERE user_id = ?", Long.class, userId));
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_genres", Integer.class));
    }

    // ------------------------------------------------------------------
    // V13: reservas de nombre de usuario (username_reservations y sus dueños)
    // ------------------------------------------------------------------

    /**
     * Esquema de la V13 tal como lo ve PostgreSQL: tipos reales
     * ({@code varchar(50)} como {@code users.username}, {@code varchar(64)},
     * {@code timestamptz}), todo obligatorio, las dos claves primarias, la
     * única clave foránea (dueños hacia su reserva, en cascada; ninguna hacia
     * {@code users}, para que la reserva sobreviva al borrado de una cuenta),
     * el índice de la limpieza y los dos {@code CHECK}. Sus consultas se
     * prueban en {@code PostgresUsernameReservationIntegrationTest}.
     */
    @Test
    void lasTablasDeReservasTienenElEsquemaEsperado() {
        Map<String, String> columns = new java.util.TreeMap<>();
        jdbc.queryForList("SELECT table_name, column_name, data_type, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name IN ('username_reservations', 'username_reservation_owners')")
                .forEach(r -> columns.put(r.get("table_name") + "." + r.get("column_name"), r.get("data_type")
                        + (r.get("character_maximum_length") == null ? "" : "(" + r.get("character_maximum_length") + ")")
                        + ("YES".equals(r.get("is_nullable")) ? " NULL" : " NOT NULL")));
        assertEquals(Map.of(
                "username_reservations.username", "character varying(50) NOT NULL",
                "username_reservations.created_at", "timestamp with time zone NOT NULL",
                "username_reservations.expires_at", "timestamp with time zone NOT NULL",
                "username_reservation_owners.username", "character varying(50) NOT NULL",
                "username_reservation_owners.email_hash", "character varying(64) NOT NULL"), columns);
        assertEquals(List.of("username"), primaryKeyColumns("username_reservations"));
        assertEquals(List.of("username", "email_hash"), primaryKeyColumns("username_reservation_owners"));

        List<Map<String, Object>> foreignKeys = jdbc.queryForList(
                "SELECT conname, confrelid::regclass::text AS target, confdeltype FROM pg_constraint"
                        + " WHERE contype = 'f' AND conrelid IN"
                        + " ('public.username_reservations'::regclass, 'public.username_reservation_owners'::regclass)");
        assertEquals(1, foreignKeys.size(), "" + foreignKeys);
        assertEquals("fk_username_reservation_owners_reservation", foreignKeys.get(0).get("conname"));
        assertEquals("username_reservations", foreignKeys.get(0).get("target"));
        assertEquals("c", String.valueOf(foreignKeys.get(0).get("confdeltype")), "ON DELETE CASCADE");

        Map<String, String> definitions = new java.util.HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));
        assertIndex(definitions, "idx_username_reservations_expires_at",
                "ON public.username_reservations USING btree (expires_at)");

        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
                String.class));
        assertTrue(checks.containsAll(Set.of("ck_username_reservations_expires_at",
                "ck_username_reservation_owners_email_hash_format")), "checks: " + checks);
    }

    /**
     * Con el código real de PostgreSQL: un nombre repetido o un dueño repetido
     * violan la clave primaria ({@code 23505}), un dueño sin reserva la clave
     * foránea ({@code 23503}), una caducidad que no va después de la creación
     * o un hash que no es SHA-256 hexadecimal en minúsculas, el {@code CHECK}
     * ({@code 23514}), y las fechas son obligatorias ({@code 23502}). Borrar
     * la reserva se lleva a sus dueños, y borrar un usuario no toca nada.
     */
    @Test
    void laBaseDeDatosRechazaReservasInvalidasYBorrarlaArrastraASusDuenos() {
        java.time.OffsetDateTime t0 = java.time.OffsetDateTime.parse("2026-11-12T10:00:00Z");
        String reservation = "INSERT INTO username_reservations (username, created_at, expires_at) VALUES (?, ?, ?)";
        String owner = "INSERT INTO username_reservation_owners (username, email_hash) VALUES (?, ?)";
        String hashA = "a".repeat(64);
        long userId = insertUser("reservado", "reservado@test.com");
        jdbc.update(reservation, "reservado", t0, t0.plusDays(1));
        jdbc.update(owner, "reservado", hashA);
        jdbc.update(owner, "reservado", "0123456789abcdef".repeat(4));

        assertEquals(UNIQUE_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(reservation, "reservado", t0.plusHours(1), t0.plusDays(2)))));
        assertEquals(UNIQUE_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(owner, "reservado", hashA))));
        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(owner, "sinreserva", hashA))));
        DataIntegrityViolationException expiry = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(reservation, "fechas", t0, t0));
        assertEquals(CHECK_VIOLATION, sqlState(expiry));
        assertTrue(expiry.getMessage().contains("ck_username_reservations_expires_at"), expiry.getMessage());
        for (String badHash : List.of("reservado@test.com", "A".repeat(64), "a".repeat(63) + "g")) {
            DataIntegrityViolationException format = assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update(owner, "reservado", badHash), badHash);
            assertEquals(CHECK_VIOLATION, sqlState(format), badHash);
            assertTrue(format.getMessage().contains("ck_username_reservation_owners_email_hash_format"),
                    format.getMessage());
        }
        assertEquals(NOT_NULL_VIOLATION, sqlState(assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(reservation, "sinfecha", t0, null))));

        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        assertEquals(2, count("username_reservation_owners"), "la reserva no depende de la cuenta");

        jdbc.update("DELETE FROM username_reservations WHERE username = 'reservado'");
        assertEquals(0, count("username_reservation_owners"));
    }

    // ------------------------------------------------------------------
    // V14: progreso de visionado (user_movie_progress y user_episode_progress)
    // ------------------------------------------------------------------

    /**
     * Esquema de la V14 tal como lo ve PostgreSQL: tipos reales
     * ({@code bigint}, {@code integer}, {@code timestamptz}), todo obligatorio,
     * las claves primarias {@code (user_id, <título>_id)}, las cuatro claves
     * foráneas en cascada hacia su tabla, los índices (por título para la
     * cascada y {@code (user_id, updated_at DESC)} para «Seguir viendo») y los
     * seis {@code CHECK}. Su comportamiento (upsert, rechazos con
     * {@code SQLSTATE}, cascadas, «Seguir viendo») se prueba en
     * {@code PostgresWatchProgressIntegrationTest}.
     */
    @Test
    void lasTablasDeProgresoTienenElEsquemaEsperado() {
        Map<String, String> columns = new java.util.TreeMap<>();
        jdbc.queryForList("SELECT table_name, column_name, data_type, is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name IN ('user_movie_progress', 'user_episode_progress')")
                .forEach(r -> columns.put(r.get("table_name") + "." + r.get("column_name"), r.get("data_type")
                        + ("YES".equals(r.get("is_nullable")) ? " NULL" : " NOT NULL")));
        Map<String, String> expected = new java.util.TreeMap<>();
        for (String[] table : new String[][] { { "user_movie_progress", "movie_id" },
                { "user_episode_progress", "episode_id" } }) {
            expected.put(table[0] + ".user_id", "bigint NOT NULL");
            expected.put(table[0] + "." + table[1], "bigint NOT NULL");
            expected.put(table[0] + ".position_seconds", "integer NOT NULL");
            expected.put(table[0] + ".duration_seconds", "integer NOT NULL");
            expected.put(table[0] + ".updated_at", "timestamp with time zone NOT NULL");
            expected.put(table[0] + ".finished_at", "timestamp with time zone NULL");
        }
        assertEquals(expected, columns);
        assertEquals(List.of("user_id", "movie_id"), primaryKeyColumns("user_movie_progress"));
        assertEquals(List.of("user_id", "episode_id"), primaryKeyColumns("user_episode_progress"));

        Map<String, String> foreignKeys = new java.util.TreeMap<>();
        jdbc.queryForList("SELECT conname, confrelid::regclass::text AS target, confdeltype FROM pg_constraint"
                        + " WHERE contype = 'f' AND conrelid IN"
                        + " ('public.user_movie_progress'::regclass, 'public.user_episode_progress'::regclass)")
                .forEach(r -> foreignKeys.put((String) r.get("conname"), r.get("target") + " " + r.get("confdeltype")));
        assertEquals(Map.of(
                "fk_movie_progress_user", "users c",
                "fk_movie_progress_movie", "movies c",
                "fk_episode_progress_user", "users c",
                "fk_episode_progress_episode", "episodes c"), foreignKeys, "todas ON DELETE CASCADE");

        Map<String, String> definitions = new java.util.HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));
        assertIndex(definitions, "idx_movie_progress_movie_id", "ON public.user_movie_progress USING btree (movie_id)");
        assertIndex(definitions, "idx_episode_progress_episode_id",
                "ON public.user_episode_progress USING btree (episode_id)");
        assertIndex(definitions, "idx_movie_progress_user_updated_at",
                "ON public.user_movie_progress USING btree (user_id, updated_at DESC)");
        assertIndex(definitions, "idx_episode_progress_user_updated_at",
                "ON public.user_episode_progress USING btree (user_id, updated_at DESC)");

        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
                String.class));
        assertTrue(checks.containsAll(Set.of(
                "ck_movie_progress_position", "ck_movie_progress_duration",
                "ck_movie_progress_position_within_duration",
                "ck_episode_progress_position", "ck_episode_progress_duration",
                "ck_episode_progress_position_within_duration")), "checks: " + checks);
    }

    // ------------------------------------------------------------------
    // V15: subtítulos (subtitles_url) y «visto alguna vez» (finished_at)
    // ------------------------------------------------------------------

    /**
     * Los seis {@code CHECK} de la V15 existen en PostgreSQL y cada uno
     * rechaza lo suyo con {@code 23514}: subtítulos que no son {@code https://},
     * subtítulos sin vídeo (también al quitar el vídeo después), una fecha de
     * «visto» posterior al guardado y una posición terminada sin fecha de
     * «visto». Lo válido (visto y ahora a medias) se acepta.
     */
    @Test
    void laV15RechazaSubtitulosYFechasDeVistoIncoherentes() {
        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
                String.class));
        assertTrue(checks.containsAll(Set.of(
                "ck_movies_subtitles_url", "ck_movies_subtitles_require_video",
                "ck_episodes_subtitles_url", "ck_episodes_subtitles_require_video",
                "ck_movie_progress_finished_at_not_after_updated_at", "ck_movie_progress_finished_has_finished_at",
                "ck_episode_progress_finished_at_not_after_updated_at",
                "ck_episode_progress_finished_has_finished_at")), "checks: " + checks);

        long movieId = insertMovie("Subtitulada", 2000);
        long seriesId = jdbc.queryForObject("INSERT INTO series (title, description, release_year, image_url,"
                + " created_at) VALUES ('Serie', 'd', 2010, 'u', now()) RETURNING id", Long.class);
        long episodeId = jdbc.queryForObject("INSERT INTO episodes (series_id, season_number, episode_number, title,"
                + " duration, video_url, created_at) VALUES (?, 1, 1, 't', 40, 'v', now()) RETURNING id",
                Long.class, seriesId);
        String subtitles = "https://example.com/es.vtt";
        jdbc.update("UPDATE movies SET subtitles_url = ? WHERE id = ?", subtitles, movieId);
        jdbc.update("UPDATE episodes SET subtitles_url = ? WHERE id = ?", subtitles, episodeId);

        assertCheck("ck_movies_subtitles_url",
                () -> jdbc.update("UPDATE movies SET subtitles_url = 'http://example.com/es.vtt' WHERE id = ?", movieId));
        assertCheck("ck_episodes_subtitles_url",
                () -> jdbc.update("UPDATE episodes SET subtitles_url = 'https://' WHERE id = ?", episodeId));
        assertCheck("ck_movies_subtitles_require_video",
                () -> jdbc.update("UPDATE movies SET video_url = NULL WHERE id = ?", movieId));
        assertCheck("ck_episodes_subtitles_require_video",
                () -> jdbc.update("UPDATE episodes SET video_url = NULL WHERE id = ?", episodeId));

        long userId = insertUser("visto", "visto@test.com");
        Instant t0 = Instant.parse("2026-11-01T20:00:00Z");
        String insert = "INSERT INTO user_movie_progress (user_id, movie_id, position_seconds, duration_seconds,"
                + " updated_at, finished_at) VALUES (?, ?, ?, 100, ?, ?)";
        assertCheck("ck_movie_progress_finished_at_not_after_updated_at",
                () -> jdbc.update(insert, userId, movieId, 2, utc(t0), utc(t0.plusNanos(1_000))));
        assertCheck("ck_movie_progress_finished_has_finished_at",
                () -> jdbc.update(insert, userId, movieId, 95, utc(t0), null));
        jdbc.update(insert, userId, movieId, 2, utc(t0), utc(t0.minusSeconds(60)));
        assertCheck("ck_episode_progress_finished_has_finished_at",
                () -> jdbc.update("INSERT INTO user_episode_progress (user_id, episode_id, position_seconds,"
                        + " duration_seconds, updated_at) VALUES (?, ?, 100, 100, now())", userId, episodeId));
        assertEquals(1, count("user_movie_progress"), "visto y ahora a medias sí se admite");
    }

    // ------------------------------------------------------------------
    // V16: imagen de cabecera (backdrop_url) e imagen del episodio (image_url)
    // ------------------------------------------------------------------

    /**
     * Los tres {@code CHECK} de la V16 existen en PostgreSQL, aceptan
     * {@code NULL}, {@code https://...} y {@code /covers/...}, y rechazan con
     * {@code 23514} lo demás (otros esquemas, mayúsculas, prefijos a secas).
     * Comprueba en el motor real que {@code SUBSTRING ... FROM 1 FOR 8} y
     * {@code CHAR_LENGTH} se comportan como en H2.
     */
    @Test
    void laV16SoloAdmiteImagenesHttpsOPortadasPropias() {
        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
                String.class));
        assertTrue(checks.containsAll(Set.of("ck_movies_backdrop_url", "ck_series_backdrop_url",
                "ck_episodes_image_url")), "checks: " + checks);

        long movieId = insertMovie("Con fondo", 2000);
        long seriesId = jdbc.queryForObject("INSERT INTO series (title, description, release_year, image_url,"
                + " created_at) VALUES ('Serie con fondo', 'd', 2010, 'u', now()) RETURNING id", Long.class);
        long episodeId = jdbc.queryForObject("INSERT INTO episodes (series_id, season_number, episode_number, title,"
                + " duration, video_url, created_at) VALUES (?, 1, 1, 't', 40, 'v', now()) RETURNING id",
                Long.class, seriesId);
        // { tabla, columna, restricción, id }
        List<Object[]> targets = List.of(
                new Object[] { "movies", "backdrop_url", "ck_movies_backdrop_url", movieId },
                new Object[] { "series", "backdrop_url", "ck_series_backdrop_url", seriesId },
                new Object[] { "episodes", "image_url", "ck_episodes_image_url", episodeId });

        for (Object[] target : targets) {
            String update = "UPDATE " + target[0] + " SET " + target[1] + " = ? WHERE id = ?";
            String select = "SELECT " + target[1] + " FROM " + target[0] + " WHERE id = ?";
            for (String valid : java.util.Arrays.asList("https://ejemplo.com/fondo.webp", "/covers/fondo.webp",
                    null)) {
                jdbc.update(update, valid, target[3]);
                assertEquals(valid, jdbc.queryForObject(select, String.class, target[3]), target[2] + ": " + valid);
            }
            for (String invalid : List.of("http://ejemplo.com/a.webp", "javascript:alert(1)",
                    "data:image/png;base64,AAAA", "HTTPS://ejemplo.com/a.webp", "https://", "/covers/", "")) {
                assertCheck((String) target[2], () -> jdbc.update(update, invalid, target[3]));
            }
        }
    }

    private void assertCheck(String constraint, Runnable statement) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, statement::run,
                constraint);
        assertEquals(CHECK_VIOLATION, sqlState(error), constraint);
        assertTrue(error.getMessage().contains(constraint), error.getMessage());
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static void assertIndex(Map<String, String> definitions, String name, String expected) {
        assertTrue(definitions.containsKey(name), "falta el índice " + name);
        assertTrue(definitions.get(name).contains(expected), definitions.get(name));
    }

    private List<String> primaryKeyColumns(String table) {
        return jdbc.queryForList(
                "SELECT k.column_name FROM information_schema.table_constraints c"
                        + " JOIN information_schema.key_column_usage k"
                        + "   ON k.constraint_name = c.constraint_name AND k.table_schema = c.table_schema"
                        + " WHERE c.constraint_type = 'PRIMARY KEY' AND c.table_schema = 'public' AND c.table_name = ?"
                        + " ORDER BY k.ordinal_position", String.class, table);
    }

    private long insertMovie(String title, int year) {
        return insertMovie(title, year, 100);
    }

    private long insertMovie(String title, int year, int duration) {
        return jdbc.queryForObject(
                "INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                        + " VALUES (?, 'd', ?, ?, 'u', 'v', CURRENT_TIMESTAMP) RETURNING id",
                Long.class, title, duration, year);
    }

    private long insertUser(String name, String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (username, email, password, role, created_at)"
                        + " VALUES (?, ?, 'p', 'USER', CURRENT_TIMESTAMP) RETURNING id",
                Long.class, name, email);
    }

    private long insertGenre(String name) {
        return jdbc.queryForObject("INSERT INTO genres (name) VALUES (?) RETURNING id", Long.class, name);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
