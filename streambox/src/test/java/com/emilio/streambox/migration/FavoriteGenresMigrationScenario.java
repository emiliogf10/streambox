package com.emilio.streambox.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Escenario de datos para probar la migración {@code V11__user_favorite_genres.sql}
 * sobre una base con cuentas, compartido por el test de H2
 * ({@link FavoriteGenresMigrationH2Test}) y el de PostgreSQL real
 * ({@code postgres.PostgresFavoriteGenresMigrationIntegrationTest}).
 *
 * <p>
 * Se usa con una base migrada hasta la {@code V10}: {@link #seedAtV10} inserta
 * cuatro cuentas (USER verificada, USER pendiente, USER con avatar y ADMIN), se
 * aplica la {@code V11} y {@link #assertMigratedByV11} comprueba que
 * <b>todas</b> quedan con la pregunta de bienvenida cerrada
 * ({@code genres_onboarded_at} rellena), con el mismo instante (el de la
 * migración, posterior a su alta), sin que cambie ningún otro dato; y que una
 * cuenta creada después nace con la pregunta pendiente. Es la decisión del
 * autor: quien ya usaba la aplicación solo ve una tarjeta en el inicio, nunca
 * la pregunta. El test falla tanto si la migración no rellena a alguna (p. ej.
 * solo a las verificadas o solo a las USER) como si deja un {@code DEFAULT}
 * que cerraría también las cuentas nuevas.
 * </p>
 */
public final class FavoriteGenresMigrationScenario {

    private static final OffsetDateTime VERIFIED = OffsetDateTime.of(2026, 10, 19, 9, 0, 0, 0, ZoneOffset.UTC);

    /**
     * Cuentas del escenario: usuario, rol, avatar, fecha de verificación (nula =
     * pendiente) y fecha de alta (todas en el pasado).
     */
    private static final Object[][] USERS = {
        { "alice", "USER", null, VERIFIED, OffsetDateTime.of(2020, 6, 15, 22, 0, 0, 0, ZoneOffset.UTC) },
        { "carol", "USER", "rocket", VERIFIED, OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC) },
        { "dave", "USER", null, null, OffsetDateTime.of(2026, 10, 20, 18, 30, 0, 0, ZoneOffset.UTC) },
        { "root", "ADMIN", "cat", VERIFIED, OffsetDateTime.of(2021, 1, 10, 8, 30, 0, 0, ZoneOffset.UTC) },
    };

    private FavoriteGenresMigrationScenario() {
    }

    /**
     * Inserta las cuentas del escenario en una base en la {@code V10} (todavía
     * sin {@code genres_onboarded_at}).
     *
     * @param jdbc conexión a la base migrada hasta la V10
     */
    public static void seedAtV10(JdbcTemplate jdbc) {
        for (Object[] user : USERS) {
            jdbc.update("INSERT INTO users (username, email, password, role, avatar, email_verified_at, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    user[0], user[0] + "@test.com", "hash-" + user[0], user[1], user[2], user[3], user[4]);
        }
        jdbc.update("INSERT INTO genres (name) VALUES ('Drama')");
    }

    /**
     * Comprueba el resultado de la {@code V11} sobre el escenario.
     *
     * @param jdbc conexión a la base recién migrada a la V11
     */
    public static void assertMigratedByV11(JdbcTemplate jdbc) {
        assertEquals(List.of(), jdbc.queryForList(
                "SELECT username FROM users WHERE genres_onboarded_at IS NULL ORDER BY username", String.class),
                "todas las cuentas anteriores a la V11 quedan con la pregunta cerrada (decisión del autor)");
        assertEquals(1, count(jdbc, "SELECT COUNT(DISTINCT genres_onboarded_at) FROM users"),
                "comparten el instante de la migración (así se identifican)");
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM users WHERE genres_onboarded_at <= created_at"),
                "es el instante de la migración, no la fecha de alta");
        // Nada más cambia
        assertEquals(List.of("alice|alice@test.com|hash-alice|USER|-|v", "carol|carol@test.com|hash-carol|USER|rocket|v",
                        "dave|dave@test.com|hash-dave|USER|-|p", "root|root@test.com|hash-root|ADMIN|cat|v"),
                jdbc.queryForList("SELECT username || '|' || email || '|' || password || '|' || role || '|'"
                        + " || COALESCE(avatar, '-') || '|'"
                        + " || CASE WHEN email_verified_at IS NULL THEN 'p' ELSE 'v' END FROM users", String.class)
                        .stream().sorted().toList());
        for (Object[] user : USERS) {
            assertEquals(((OffsetDateTime) user[4]).toInstant(), jdbc.queryForObject(
                    "SELECT created_at FROM users WHERE username = ?", OffsetDateTime.class, user[0]).toInstant(),
                    "created_at intacto: " + user[0]);
        }
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM user_favorite_genres"), "la tabla de gustos nace vacía");
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM genres"));

        // Una cuenta creada después de la V11 sin indicar la columna nace con la
        // pregunta pendiente (no hay DEFAULT): solo la migración cerró las que existían
        jdbc.update("INSERT INTO users (username, email, password, role, created_at)"
                + " VALUES ('nueva', 'nueva@test.com', 'h', 'USER', CURRENT_TIMESTAMP)");
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM users WHERE username = 'nueva' AND genres_onboarded_at IS NULL"));
        jdbc.update("DELETE FROM users WHERE username = 'nueva'");
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
