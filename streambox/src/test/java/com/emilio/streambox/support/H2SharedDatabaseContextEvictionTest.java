package com.emilio.streambox.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.emilio.streambox.StreamboxApplication;

/**
 * Protege la base H2 compartida de los tests frente a la <b>expulsión de un
 * contexto</b> de la caché de Spring ({@code spring.test.context.cache.maxSize},
 * 32 por defecto).
 *
 * <p>
 * <b>El fallo que evita.</b> Con más contextos que el tope, Spring cierra el más
 * antiguo y, con él, su pool de Hikari. La base no se cierra
 * ({@code DB_CLOSE_DELAY=-1}), pero desde ese momento cualquier {@code INSERT}
 * en {@code users} fallaba con {@code Check constraint invalid: "CK_USERS_ROLE"}
 * causado por {@code The database has been closed [90098]}. La causa es de H2
 * (2.3 y 2.4): una restricción {@code CHECK (col IN ('A', 'B'))} se compila en
 * un {@code ConditionInConstantSet} cuyo {@code TreeSet} usa como comparador la
 * <b>sesión que ejecutó el DDL</b>; al cerrarse esa conexión, la sesión pierde
 * su {@code Database} y cada comprobación posterior lanza el 90098, aunque la
 * base siga viva. Las migraciones las ejecutaba Flyway con una conexión del pool
 * del primer contexto, así que expulsar ese contexto rompía la restricción para
 * todos los demás. DevTools no interviene (se desactiva solo en los tests).
 * </p>
 *
 * <p>
 * <b>El arreglo</b> es {@link H2FlywaySessionTestAutoConfiguration}: en los
 * tests, Flyway migra las bases H2 en memoria con una conexión que vive lo que
 * la JVM, nunca con una del pool.
 * </p>
 *
 * <p>
 * No usa la caché de contextos (arranca la aplicación con
 * {@link SpringApplication} sobre una base H2 propia), así que no suma un
 * contexto más a los de la suite.
 * </p>
 */
class H2SharedDatabaseContextEvictionTest {

    /**
     * Documenta la causa en H2, sin Spring: si se cierra la conexión que creó la
     * restricción {@code CHECK ... IN}, la restricción deja de poder evaluarse
     * aunque otra conexión mantenga la base abierta. Si una versión futura de H2
     * lo corrige, este test fallará y avisará de que el arreglo de los tests
     * (la conexión de migración que nunca se cierra) ya no hace falta.
     */
    @Test
    void enH2CerrarLaConexionQueCreoUnCheckInRompeLaRestriccionParaLasDemas() throws SQLException {
        String url = uniqueUrl("h2check");
        try (Connection keeper = DriverManager.getConnection(url, "sa", "")) {
            try (Connection ddl = DriverManager.getConnection(url, "sa", "");
                    Statement statement = ddl.createStatement()) {
                statement.execute("CREATE TABLE t (role VARCHAR(20) NOT NULL, CONSTRAINT ck_t_role CHECK (role IN ('USER', 'ADMIN')))");
            }

            SQLException error = assertThrows(SQLException.class,
                    () -> execute(keeper, "INSERT INTO t (role) VALUES ('USER')"));
            assertEquals(23514, error.getErrorCode(), "H2 debería informar de una restricción CHECK inválida");
            assertTrue(causeChainContains(error, "The database has been closed"),
                    "la causa debería ser la sesión cerrada que creó la restricción");

            execute(keeper, "SHUTDOWN");
        }
    }

    /**
     * Reproduce la expulsión de un contexto de la caché: arranca la aplicación
     * (Flyway migra una base H2 nueva), abre otra conexión que mantiene la base
     * viva, cierra el contexto y comprueba que {@code ck_users_role} sigue
     * funcionando en las dos direcciones: acepta {@code USER} y rechaza un rol
     * inventado. Sin {@link H2FlywaySessionTestAutoConfiguration}, el primer
     * {@code INSERT} falla con {@code CK_USERS_ROLE} + 90098.
     */
    @Test
    void cerrarElContextoQueMigroLaBaseNoRompeSusRestriccionesParaLosDemas() throws SQLException {
        String url = uniqueUrl("expulsion");
        try (Connection keeper = openAfterMigrating(url)) {
            execute(keeper, "INSERT INTO users (username, email, password, role, created_at) "
                    + "VALUES ('superviviente', 'superviviente@test.com', 'x', 'USER', CURRENT_TIMESTAMP)");

            SQLException invalidRole = assertThrows(SQLException.class,
                    () -> execute(keeper, "INSERT INTO users (username, email, password, role, created_at) "
                            + "VALUES ('intruso', 'intruso@test.com', 'x', 'ROOT', CURRENT_TIMESTAMP)"));
            assertEquals(23513, invalidRole.getErrorCode(),
                    "la restricción debe seguir rechazando roles inválidos (23513), no fallar al evaluarse (23514)");

            execute(keeper, "SHUTDOWN");
        }
    }

    /**
     * Arranca la aplicación con el perfil {@code test} sobre {@code url}, abre
     * una conexión propia mientras el contexto está vivo y cierra el contexto
     * (como hace la caché al expulsarlo). Devuelve esa conexión.
     */
    private static Connection openAfterMigrating(String url) throws SQLException {
        Connection keeper;
        try (ConfigurableApplicationContext context = new SpringApplication(StreamboxApplication.class).run(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + url,
                "--server.port=0")) {
            keeper = DriverManager.getConnection(url, "sa", "");
        }
        return keeper;
    }

    /** URL de una base H2 en memoria nueva con las mismas opciones que la compartida de los tests. */
    private static String uniqueUrl(String prefix) {
        return "jdbc:h2:mem:" + prefix + "_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean causeChainContains(Throwable error, String text) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
