package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.hibernate.engine.jdbc.spi.SQLExceptionLogging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * La categoría de log con la que Hibernate vuelca los errores de JDBC está
 * apagada en todos los perfiles.
 *
 * <p>
 * <b>Por qué.</b> Ese volcado ({@code WARN}, antes de que el servicio traduzca
 * la excepción) lleva el mensaje de la base de datos, y en una violación de
 * unicidad ese mensaje trae el valor de la fila: el email de quien se registra
 * (ver el comentario en {@code application.properties}). Los tests de
 * integración ({@code IntegrityViolationTranslationIntegrationTest} con H2 y
 * {@code PostgresIntegrityViolationLogPrivacyQaIntegrationTest} con
 * PostgreSQL) prueban el efecto; este protege dos cosas que ellos no ven:
 * </p>
 * <ul>
 * <li>que la propiedad apunta a la categoría <b>real</b> de la versión de
 * Hibernate en uso (se lee de su propia constante): si una actualización la
 * renombra, la propiedad dejaría de hacer nada en silencio;</li>
 * <li>que ningún perfil la vuelve a encender, en especial {@code prod}, cuyo
 * arranque no se prueba con un {@code @SpringBootTest} (necesita secretos y
 * PostgreSQL).</li>
 * </ul>
 */
class HibernateJdbcErrorLogPropertiesTest {

    /** Propiedad de Spring Boot que fija el nivel de la categoría. */
    private static final String PROPERTY = "logging.level.org.hibernate.orm.jdbc.error";

    /** La propiedad nombra la categoría que usa de verdad {@code SqlExceptionHelper}. */
    @Test
    void laPropiedadApuntaALaCategoriaRealDeHibernate() {
        assertEquals(PROPERTY, "logging.level." + SQLExceptionLogging.ERROR_NAME);
    }

    /**
     * Con cada perfil, la categoría queda en {@code OFF}. Reproduce la
     * precedencia de Spring Boot: el archivo del perfil va delante (gana) de
     * {@code application.properties}.
     *
     * @param profile perfil cuyo archivo se carga encima del común
     * @throws IOException si no se puede leer un archivo de propiedades
     */
    @ParameterizedTest
    @ValueSource(strings = { "dev", "prod", "test" })
    void laCategoriaQuedaApagadaEnCadaPerfil(String profile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        addLast(environment, "application-" + profile + ".properties");
        addLast(environment, "application.properties");

        assertEquals("OFF", environment.getProperty(PROPERTY));
    }

    private static void addLast(StandardEnvironment environment, String file) throws IOException {
        for (PropertySource<?> source : new PropertiesPropertySourceLoader()
                .load(file, new ClassPathResource(file))) {
            environment.getPropertySources().addLast(source);
        }
    }
}
