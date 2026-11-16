package com.emilio.streambox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Protege la configuración de base de datos del perfil {@code test}: cada
 * contexto de Spring tiene su propia base H2 en memoria y no depende del ciclo
 * de vida de ningún otro.
 *
 * <p><b>El fallo que evita</b> (105 errores en la suite completa con Docker en
 * Windows, 2026-11-12): con una única base compartida ({@code jdbc:h2:mem:streambox}),
 * el esquema lo creaba la conexión de Flyway del primer contexto. H2 2.4.240
 * guarda en cada {@code CHECK (... IN ('USER','ADMIN'))} un {@code TreeSet} cuyo
 * comparador es la <em>sesión</em> que ejecutó el {@code CREATE} (en 2.3.232 era
 * el {@code CompareMode} de la base). Cuando la caché de contextos (máx. 32)
 * expulsaba aquel primer contexto, su pool de Hikari cerraba esa conexión y
 * todos los {@code INSERT} en {@code users} de los demás contextos fallaban con
 * «Check constraint invalid: CK_USERS_ROLE», causado por «The database has been
 * closed». Solo aparecía cuando había más de 32 contextos (con los de
 * PostgreSQL) y según el orden de las clases, por eso no se veía en el CI.</p>
 *
 * <p>Este test arranca dos aplicaciones reales con el perfil {@code test},
 * cierra la primera (la que, con una base compartida y ejecutado solo, habría
 * creado el esquema; lo mismo que hace la caché al expulsarla) y comprueba que
 * la segunda sigue insertando usuarios. La aserción de URLs distintas hace que
 * falle de forma determinista con la configuración antigua, aunque otro
 * contexto de la suite ya hubiera creado la base compartida.</p>
 */
class TestDatabasePerContextIntegrationTest {

    /** Arranca la aplicación con el perfil de tests y un puerto libre. */
    private static ConfigurableApplicationContext start() {
        return new SpringApplication(StreamboxApplication.class).run(
                "--spring.profiles.active=test", "--server.port=0");
    }

    private static String jdbcUrl(ConfigurableApplicationContext context) {
        return context.getBean(HikariDataSource.class).getJdbcUrl();
    }

    /**
     * Dos contextos usan bases distintas y cerrar uno no rompe las
     * restricciones {@code CHECK} del otro.
     */
    @Test
    void cerrarUnContextoNoRompeLaBaseDeOtro() {
        // El primero en arrancar es el que crearía el esquema con una base compartida.
        ConfigurableApplicationContext evicted = start();
        String firstUrl = jdbcUrl(evicted);
        try (ConfigurableApplicationContext survivor = start()) {
            // Cierra su pool de Hikari, como al salir de la caché de contextos.
            evicted.close();

            String survivorUrl = jdbcUrl(survivor);
            assertThat(survivorUrl)
                    .as("cada contexto debe tener su propia base H2 en memoria")
                    .isNotEqualTo(firstUrl)
                    .startsWith("jdbc:h2:mem:streambox_")
                    .contains("MODE=PostgreSQL");

            User user = new User();
            user.setEmailVerifiedAt(Instant.now());
            user.setUsername("contexto-superviviente");
            user.setEmail("superviviente@contextos.test");
            user.setPassword(survivor.getBean(PasswordEncoder.class).encode("Contraseña-Contexto-2026"));
            user.setRole(Role.USER);
            user.setCreatedAt(Instant.now());
            UserRepository users = survivor.getBean(UserRepository.class);

            // Antes del arreglo, este INSERT fallaba al evaluar CK_USERS_ROLE.
            users.save(user);
            assertThat(users.findByEmail("superviviente@contextos.test")).isPresent();
        } finally {
            evicted.close(); // idempotente: por si falló antes de cerrarlo
        }
    }
}
