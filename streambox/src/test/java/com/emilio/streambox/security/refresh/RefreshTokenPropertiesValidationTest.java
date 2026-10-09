package com.emilio.streambox.security.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/**
 * Valores por defecto y validación de {@link RefreshTokenProperties}: una
 * configuración incoherente impide arrancar con un mensaje que nombra la
 * propiedad.
 */
class RefreshTokenPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(RefreshTokenProperties.class)
    static class TestConfig {
    }

    /** Sin variables de entorno ni propiedades de la JVM de la máquina. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(TestConfig.class);

    @Test
    void losValoresPorDefectoSonLosDelDiseno() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RefreshTokenProperties properties = context.getBean(RefreshTokenProperties.class);
            assertThat(properties.ttl()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.familyTtl()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.reuseGrace()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.cleanup().enabled()).isTrue();
            assertThat(properties.cleanup().interval()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.cleanup().initialDelay()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.cleanup().retention()).isEqualTo(Duration.ofDays(3));
        });
    }

    @ParameterizedTest
    @CsvSource({
            "streambox.auth.refresh.ttl, 0s, streambox.auth.refresh.ttl",
            "streambox.auth.refresh.ttl, -1d, streambox.auth.refresh.ttl",
            "streambox.auth.refresh.family-ttl, 6d, streambox.auth.refresh.family-ttl",
            "streambox.auth.refresh.reuse-grace, -1s, streambox.auth.refresh.reuse-grace",
            "streambox.auth.refresh.reuse-grace, 2m, streambox.auth.refresh.reuse-grace",
            "streambox.auth.refresh.cleanup.interval, 0s, streambox.auth.refresh.cleanup.interval",
            "streambox.auth.refresh.cleanup.retention, -1d, streambox.auth.refresh.cleanup.retention",
            "streambox.auth.refresh.cleanup.initial-delay, -1s, streambox.auth.refresh.cleanup.initial-delay"
    })
    void unValorIncoherenteImpideArrancar(String property, String value, String expectedInMessage) {
        runner.withPropertyValues(property + "=" + value).run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining(expectedInMessage));
    }

    @Test
    void sinGraciaYConFamiliaIgualAlTokenEsValido() {
        runner.withPropertyValues("streambox.auth.refresh.reuse-grace=0s",
                "streambox.auth.refresh.family-ttl=7d").run(context -> assertThat(context).hasNotFailed());
    }
}
