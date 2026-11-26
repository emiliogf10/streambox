package com.emilio.streambox.demo;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * El script opcional {@code docs/catalogo-demostracion.sql} contra el esquema
 * real de Flyway en <strong>H2</strong> (modo PostgreSQL). Los casos están en
 * {@link DemoCatalogScriptContract}; su gemelo contra PostgreSQL real es
 * {@code PostgresDemoCatalogScriptIntegrationTest}.
 *
 * <p>
 * Mismas propiedades que {@code UsernameReservationRepositoryIntegrationTest}
 * (reutiliza su contexto de Spring). Sin {@code @Transactional}: el script
 * confirma con su propio {@code COMMIT} en otra conexión, así que se limpia
 * antes y después de cada test.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DemoCatalogScriptTest implements DemoCatalogScriptContract {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void setUp() {
        cleanCatalog();
    }

    @AfterEach
    void tearDown() {
        cleanCatalog();
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Vacía el catálogo en el orden de las claves foráneas: las series
     * (arrastran episodios y sus géneros), las películas (arrastran
     * {@code movie_genres}, favoritos, «me gusta» y progreso) y, por último,
     * los géneros, que ya nadie usa.
     */
    private void cleanCatalog() {
        jdbc.update("DELETE FROM series");
        jdbc.update("DELETE FROM movies");
        jdbc.update("DELETE FROM genres");
    }
}
