package com.emilio.streambox.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Autoconfiguración <b>solo de tests</b> (registrada en
 * {@code src/test/resources/META-INF/spring/...AutoConfiguration.imports}):
 * Flyway migra las bases H2 en memoria con una conexión que <b>no se cierra
 * nunca</b> mientras viva la JVM, en vez de con una conexión del pool del
 * contexto.
 *
 * <p>
 * <b>Por qué hace falta.</b> En H2, una restricción
 * {@code CHECK (col IN ('A', 'B'))} (p. ej. {@code ck_users_role}) guarda como
 * comparador la sesión que ejecutó el DDL. Si esa conexión se cierra, la
 * restricción ya no se puede evaluar ({@code Check constraint invalid} causado
 * por {@code The database has been closed [90098]}) aunque la base siga abierta
 * por {@code DB_CLOSE_DELAY=-1}. Todos los contextos de la suite comparten la
 * base {@code jdbc:h2:mem:streambox} y la migra el primero que arranca: cuando la
 * caché de contextos de Spring lo expulsaba (más de 32 contextos), cerraba su
 * pool y rompía los {@code INSERT} en {@code users} de todos los demás. Con esta
 * conexión, el DDL lo ejecuta siempre una sesión que vive lo que la JVM, así que
 * da igual qué contexto se cierre. Lo prueba
 * {@link H2SharedDatabaseContextEvictionTest}.
 * </p>
 *
 * <p>
 * <b>Alcance.</b> Solo cambia la conexión de Flyway cuando la base es H2 en
 * memoria con {@code DB_CLOSE_DELAY=-1} (las de los tests); con PostgreSQL
 * (Testcontainers) no hace nada. No añade contextos a la caché: las
 * autoconfiguraciones no forman parte de su clave. El pool del contexto sigue
 * siendo el que usan la aplicación y los tests.
 * </p>
 */
@AutoConfiguration
@ConditionalOnClass({ Flyway.class, HikariDataSource.class })
public class H2FlywaySessionTestAutoConfiguration {

    /**
     * Una conexión de migración por base (URL + usuario), compartida por todos
     * los contextos de la JVM. Nunca se cierra: {@code SingleConnectionDataSource}
     * con {@code suppressClose} ignora los {@code close()} de Flyway.
     */
    private static final Map<String, DataSource> MIGRATION_CONNECTIONS = new ConcurrentHashMap<>();

    /**
     * Sustituye el {@code DataSource} de Flyway por la conexión permanente de la
     * base, si es una H2 en memoria que debe sobrevivir a sus conexiones. Spring
     * Boot aplica los {@link FlywayConfigurationCustomizer} después de fijar el
     * {@code DataSource} y de resolver {@code {vendor}}, así que lo demás
     * (ubicaciones, baseline, callbacks) sigue saliendo de la configuración real.
     */
    @Bean
    FlywayConfigurationCustomizer h2PermanentMigrationConnection() {
        return configuration -> {
            if (configuration.getDataSource() instanceof HikariDataSource pool && isSharedInMemoryH2(pool.getJdbcUrl())) {
                String user = pool.getUsername() == null ? "" : pool.getUsername();
                String password = pool.getPassword() == null ? "" : pool.getPassword();
                configuration.dataSource(MIGRATION_CONNECTIONS.computeIfAbsent(pool.getJdbcUrl() + "|" + user,
                        key -> new SingleConnectionDataSource(pool.getJdbcUrl(), user, password, true)));
            }
        };
    }

    /** H2 en memoria configurada para sobrevivir a sus conexiones (como la de {@code application-test.properties}). */
    private static boolean isSharedInMemoryH2(String url) {
        return url != null && url.startsWith("jdbc:h2:mem:") && url.toUpperCase().contains("DB_CLOSE_DELAY=-1");
    }
}
