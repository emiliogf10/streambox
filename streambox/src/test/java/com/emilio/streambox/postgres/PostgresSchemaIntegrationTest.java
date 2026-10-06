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

import jakarta.persistence.EntityManagerFactory;

/**
 * Esquema que producen las migraciones de Flyway sobre un <b>PostgreSQL real</b>
 * (equivalente a {@code FlywaySchemaIntegrationTest}, que usa H2).
 *
 * <p>
 * Que el contexto de Spring arranque ya demuestra dos cosas: que todas las
 * migraciones ({@code V1}..{@code V3}) se aplican en PostgreSQL desde cero y que Hibernate
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
    }

    // ------------------------------------------------------------------
    // Flyway y Hibernate
    // ------------------------------------------------------------------

    /**
     * En una base de datos vacía Flyway aplica exactamente V1, V2 y V3 (sin
     * fila de baseline: esa solo aparece al adoptar una base existente) y todas
     * terminan bien. Protege contra una migración nueva que falle en PostgreSQL
     * pero no en H2. Al añadir una migración, hay que añadir su versión aquí.
     */
    @Test
    void flywayAplicaTodasLasMigracionesDesdeCeroSinBaseline() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");

        assertEquals(List.of("1", "2", "3"), history.stream().map(r -> (String) r.get("version")).toList(),
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

    /** Longitudes de los {@code VARCHAR} y obligatoriedad de cada columna. */
    @Test
    void lasLongitudesYLaObligatoriedadDeLasColumnasSonLasDeLasEntidades() {
        Map<String, Integer> expectedLengths = Map.ofEntries(
                Map.entry("users.username", 50), Map.entry("users.email", 100),
                Map.entry("users.password", 255), Map.entry("users.role", 20),
                Map.entry("genres.name", 50),
                Map.entry("movies.title", 150), Map.entry("movies.description", 1000),
                Map.entry("movies.image_url", 500), Map.entry("movies.video_url", 500));

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
        // Ninguna columna admite NULL
        assertTrue(columns.stream().allMatch(c -> "NO".equals(c.get("is_nullable"))),
                "hay columnas que admiten NULL: " + columns.stream()
                        .filter(c -> !"NO".equals(c.get("is_nullable"))).toList());
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
     * {@code PostgresSeriesSchemaIntegrationTest}).
     */
    @Test
    void existenLasRestriccionesDeUnicidadYSeRechazanLosDuplicadosConElCodigoReal() {
        Set<String> uniques = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'u' AND connamespace = 'public'::regnamespace",
                String.class));
        assertEquals(Set.of("uk_users_username", "uk_users_email", "uk_genres_name",
                "uk_episodes_series_season_episode"), uniques);

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

    /** Existen las tres restricciones CHECK con su nombre. */
    @Test
    void existenLasRestriccionesCheckConNombre() {
        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace",
                String.class));

        assertTrue(checks.containsAll(Set.of("ck_users_role", "ck_movies_duration", "ck_movies_release_year")),
                "checks: " + checks);
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
    // Utilidades
    // ------------------------------------------------------------------

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
