package com.emilio.streambox.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Comprueba que la aplicación se niega a arrancar con una configuración JWT
 * insegura, en lugar de fallar más tarde al firmar el primer token.
 */
class JwtPropertiesValidationTest {

    private static final String VALID_SECRET = "a".repeat(32);

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

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
