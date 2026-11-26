package com.emilio.streambox.postgres;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.emilio.streambox.demo.DemoCatalogScriptContract;

/**
 * El script opcional {@code docs/catalogo-demostracion.sql} contra
 * <strong>PostgreSQL real</strong> (Testcontainers): el motor en el que lo
 * ejecutará el autor (Supabase). Mismos casos que la versión H2
 * ({@code DemoCatalogScriptTest}), definidos en
 * {@link DemoCatalogScriptContract}.
 *
 * <p>
 * Qué puede cambiar el motor y por eso se repite: el {@code BEGIN}/{@code COMMIT}
 * explícito sobre una conexión en autocommit, la transacción abortada tras un
 * error, los tipos que PostgreSQL deduce de las listas {@code VALUES}, la
 * función {@code streambox_unaccent} de verdad (con la extensión
 * {@code unaccent}, no el alias de H2) y que el {@code NOT EXISTS} de la
 * asignación de géneros vea la tabla como estaba al empezar la sentencia.
 * </p>
 */
class PostgresDemoCatalogScriptIntegrationTest extends PostgresIntegrationTestSupport
        implements DemoCatalogScriptContract {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }
}
