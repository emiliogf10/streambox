package com.emilio.streambox.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Escenario de datos para probar la migración {@code V9__email_verification.sql}
 * sobre una base con cuentas, compartido por el test de H2
 * ({@link EmailVerificationMigrationH2Test}) y el de PostgreSQL real
 * ({@code postgres.PostgresEmailVerificationMigrationIntegrationTest}).
 *
 * <p>
 * Se usa con una base migrada hasta la {@code V8}: {@link #seedAtV8} inserta
 * cuatro cuentas (dos USER, una de ellas con avatar, y dos ADMIN), se aplica la
 * {@code V9} y {@link #assertMigratedByV9} comprueba que <b>solo las ADMIN</b>
 * quedan verificadas, con el mismo instante (el de la migración, posterior a su
 * {@code created_at}), que <b>las USER quedan pendientes</b>
 * ({@code email_verified_at} nula) y que no cambia ningún otro dato. Es la
 * decisión del autor para su base de Supabase: probar el flujo de verificación
 * con cuentas reales sin dejar fuera al administrador. El test falla tanto si
 * la migración vuelve a marcar a todas como si no marca a ninguna.
 * </p>
 */
public final class EmailVerificationMigrationScenario {

    /**
     * Cuentas del escenario: usuario, email, rol, avatar y fecha de alta (todas
     * en el pasado). Hay dos ADMIN para que «comparten el mismo instante» no se
     * cumpla por tener una sola fila.
     */
    private static final Object[][] USERS = {
        { "alice", "alice@test.com", "USER", null, OffsetDateTime.of(2020, 6, 15, 22, 0, 0, 0, ZoneOffset.UTC) },
        { "root", "root@test.com", "ADMIN", null, OffsetDateTime.of(2021, 1, 10, 8, 30, 0, 0, ZoneOffset.UTC) },
        { "carol", "carol@test.com", "USER", "rocket", OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC) },
        { "boss", "boss@test.com", "ADMIN", "cat", OffsetDateTime.of(2026, 10, 2, 9, 15, 0, 0, ZoneOffset.UTC) },
    };

    private EmailVerificationMigrationScenario() {
    }

    /**
     * Inserta las cuentas del escenario en una base en la {@code V8} (todavía
     * sin {@code email_verified_at}).
     *
     * @param jdbc conexión a la base migrada hasta la V8
     */
    public static void seedAtV8(JdbcTemplate jdbc) {
        for (Object[] user : USERS) {
            jdbc.update("INSERT INTO users (username, email, password, role, avatar, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", user[0], user[1], "hash-" + user[0], user[2], user[3], user[4]);
        }
    }

    /**
     * Comprueba el resultado de la {@code V9} sobre el escenario.
     *
     * @param jdbc conexión a la base recién migrada a la V9
     */
    public static void assertMigratedByV9(JdbcTemplate jdbc) {
        // Lista exacta en los dos sentidos: falla si se marca a todas o a ninguna
        assertEquals(List.of("boss", "root"), usernames(jdbc, "email_verified_at IS NOT NULL"),
                "solo las cuentas ADMIN anteriores a la V9 quedan verificadas");
        assertEquals(List.of("alice", "carol"), usernames(jdbc, "email_verified_at IS NULL"),
                "las cuentas USER anteriores a la V9 quedan pendientes (decisión del autor)");
        assertEquals(1, count(jdbc, "SELECT COUNT(DISTINCT email_verified_at) FROM users WHERE role = 'ADMIN'"),
                "los ADMIN comparten el instante de la migración (así se identifican)");
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM users WHERE email_verified_at <= created_at"),
                "es el instante de la migración, no la fecha de alta");
        // Nada más cambia
        assertEquals(List.of("alice|alice@test.com|hash-alice|USER|-", "boss|boss@test.com|hash-boss|ADMIN|cat",
                        "carol|carol@test.com|hash-carol|USER|rocket", "root|root@test.com|hash-root|ADMIN|-"),
                jdbc.queryForList("SELECT username || '|' || email || '|' || password || '|' || role || '|'"
                        + " || COALESCE(avatar, '-') FROM users", String.class).stream().sorted().toList());
        for (Object[] user : USERS) {
            assertEquals(((OffsetDateTime) user[4]).toInstant(), jdbc.queryForObject(
                    "SELECT created_at FROM users WHERE username = ?", OffsetDateTime.class, user[0]).toInstant(),
                    "created_at intacto: " + user[0]);
        }
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM account_tokens"), "la tabla de tokens nace vacía");

        // Una cuenta creada después de la V9 sin indicar la columna nace
        // pendiente, también si es ADMIN: solo la migración verificó a los
        // ADMIN que ya existían (no hay DEFAULT ni nada que dependa del rol)
        jdbc.update("INSERT INTO users (username, email, password, role, created_at)"
                + " VALUES ('nueva', 'nueva@test.com', 'h', 'ADMIN', CURRENT_TIMESTAMP)");
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM users WHERE username = 'nueva' AND email_verified_at IS NULL"));
        jdbc.update("DELETE FROM users WHERE username = 'nueva'");
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    /** Nombres de usuario (ordenados) de las cuentas que cumplen la condición. */
    private static List<String> usernames(JdbcTemplate jdbc, String condition) {
        return jdbc.queryForList("SELECT username FROM users WHERE " + condition + " ORDER BY username", String.class);
    }
}
