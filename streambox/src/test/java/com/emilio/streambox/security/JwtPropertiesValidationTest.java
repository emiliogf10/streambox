package com.emilio.streambox.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/**
 * Comprueba que la aplicación se niega a arrancar con una configuración JWT
 * insegura, en lugar de fallar más tarde al firmar el primer token.
 *
 * <h2>Aislamiento del entorno de la máquina</h2>
 * <p>
 * {@link ApplicationContextRunner} crea un {@code StandardEnvironment}, que
 * incluye las variables de entorno y las propiedades de la JVM. Gracias al
 * <em>relaxed binding</em> de Spring Boot, la variable {@code JWT_SECRET} del
 * sistema se enlaza a {@code jwt.secret}. Por eso, en una máquina de desarrollo
 * que ya la tiene definida (necesaria para arrancar la app), el caso «secreto
 * ausente» dejaba de estar ausente y el test fallaba, mientras que en CI
 * (sin la variable) pasaba.
 * </p>
 * <p>
 * Para que el resultado no dependa de quien ejecute la suite, el
 * {@code runner} retira del entorno esas dos fuentes de propiedades y deja
 * como única configuración la que cada test declara con
 * {@code withPropertyValues}. Se elige esto en lugar de fijar
 * {@code jwt.secret=} a vacío porque así «ausente» sigue siendo realmente
 * «ausente» (un valor vacío es otro caso, cubierto por
 * {@link #secretoEnBlancoImpideArrancar()}) y no se debilita ninguna
 * comprobación.
 * </p>
 */
class JwtPropertiesValidationTest {

    private static final String VALID_SECRET = "a".repeat(32);

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class TestConfig {
    }

    /**
     * Contexto mínimo y hermético: sin variables de entorno ni propiedades de
     * sistema (ver Javadoc de la clase). El inicializador se ejecuta antes de
     * enlazar las propiedades, y {@code withPropertyValues} añade sus valores a
     * otra fuente, que no se ve afectada.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(TestConfig.class);

    @Test
    void secretoValidoArrancaYUsa24HorasPorDefecto() {
        runner.withPropertyValues("jwt.secret=" + VALID_SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            JwtProperties properties = context.getBean(JwtProperties.class);
            assertThat(properties.secret()).isEqualTo(VALID_SECRET);
            assertThat(properties.expirationHours()).isEqualTo(24);
        });
    }

    @Test
    void secretoDemasiadoCortoImpideArrancar() {
        runner.withPropertyValues("jwt.secret=" + "a".repeat(31)).run(context ->
                assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("al menos 32 caracteres"));
    }

    @Test
    void secretoAusenteImpideArrancar() {
        runner.run(context ->
                assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("jwt.secret es obligatorio"));
    }

    @Test
    void secretoEnBlancoImpideArrancar() {
        runner.withPropertyValues("jwt.secret=").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void duracionCeroONegativaImpideArrancar() {
        runner.withPropertyValues("jwt.secret=" + VALID_SECRET, "jwt.expiration-hours=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("jwt.expiration-hours"));
    }
}
