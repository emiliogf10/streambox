package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * El cuerpo de error de Spring Boot ({@code /error}) nunca incluye el mensaje de
 * la excepción, la traza ni los errores de validación, tampoco en el perfil
 * {@code dev} con spring-boot-devtools.
 *
 * <p>
 * <b>Por qué importa.</b> {@code SecurityConfig} permite el despacho de error
 * para que un {@code sendError} o una excepción en un filtro lleguen al cliente
 * con su código real y no como un 401 falso. Hoy ese cuerpo lo escribe
 * {@code ApiErrorController} (que sustituye a {@code BasicErrorController} y
 * nunca incluye mensaje ni traza), así que estas opciones son una defensa extra:
 * siguen protegiendo si alguien quitara ese controlador o si algún otro
 * componente de Spring Boot usara {@code ErrorAttributes}. devtools (presente
 * al arrancar con {@code spring-boot:run}) pone las tres a {@code always}; con
 * {@code BasicErrorController}, en desarrollo, un fallo en un filtro habría
 * devuelto su traza completa. {@code application.properties} las fija a
 * {@code never}.
 * </p>
 *
 * <p>
 * <b>Cómo se prueba.</b> Con un {@code @SpringBootTest} no se puede: devtools se
 * desactiva solo dentro de JUnit. Este test reproduce el orden de precedencia
 * real: devtools añade sus valores como la última fuente de propiedades (la de
 * menor prioridad), detrás de {@code application.properties}. Sin las tres
 * líneas, el valor resultante sería {@code always} y el test falla.
 * </p>
 */
class ErrorDetailsNeverExposedPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.web.error.include-message",
            "spring.web.error.include-stacktrace",
            "spring.web.error.include-binding-errors" })
    void applicationPropertiesGanaALosValoresDeDevtools(String property) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new PropertiesPropertySourceLoader()
                .load("application.properties", new ClassPathResource("application.properties"))) {
            environment.getPropertySources().addLast(source);
        }
        // Lo mismo que hace DevToolsPropertyDefaultsPostProcessor: addLast.
        environment.getPropertySources().addLast(new MapPropertySource("devtools", Map.of(property, "always")));

        assertEquals("never", environment.getProperty(property));
    }
}
