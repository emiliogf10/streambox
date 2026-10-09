package com.emilio.streambox.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/**
 * Valida el enlace de {@link RateLimitProperties}: los valores por defecto de
 * {@code application.properties} y el rechazo de límites no positivos, que
 * impide arrancar con un tope de claves inválido (un tope de 0 o negativo
 * rompería los contadores o los dejaría sin límite efectivo).
 */
class RateLimitPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class TestConfig {
    }

    /** Contexto hermético: sin variables de entorno ni propiedades de la JVM. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "streambox.security.rate-limit.login.max-requests=10",
                    "streambox.security.rate-limit.login.window=1m",
                    "streambox.security.rate-limit.register.max-requests=5",
                    "streambox.security.rate-limit.register.window=1h",
                    "streambox.security.rate-limit.refresh.max-requests=30",
                    "streambox.security.rate-limit.refresh.window=1m",
                    "streambox.security.rate-limit.lockout.max-failures=5",
                    "streambox.security.rate-limit.lockout.window=15m",
                    "streambox.security.rate-limit.lockout.max-known-ips=5",
                    "streambox.security.rate-limit.lockout.known-ip-ttl=30d");

    @Test
    void enlazaElTopeDeClavesYLosLimitesDeIpsConocidas() {
        runner.withPropertyValues("streambox.security.rate-limit.max-keys=50000").run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.maxKeys()).isEqualTo(50_000);
            assertThat(properties.lockout().maxKnownIps()).isEqualTo(5);
            assertThat(properties.lockout().knownIpTtl()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.refresh().maxRequests()).isEqualTo(30);
            assertThat(properties.refresh().window()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    /** El límite del refresh (tarea 29) es obligatorio y positivo, como los demás. */
    @Test
    void unLimiteDeRefreshNoPositivoImpideArrancar() {
        runner.withPropertyValues(
                        "streambox.security.rate-limit.max-keys=100000",
                        "streambox.security.rate-limit.refresh.max-requests=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unTopeDeClavesCeroONegativoImpideArrancar() {
        runner.withPropertyValues("streambox.security.rate-limit.max-keys=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("streambox.security.rate-limit.max-keys=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void sinTopeDeClavesImpideArrancar() {
        // El valor por defecto de int (0) no es positivo: nunca se arranca sin tope explícito.
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unMaximoDeIpsConocidasNoPositivoImpideArrancar() {
        runner.withPropertyValues(
                        "streambox.security.rate-limit.max-keys=100000",
                        "streambox.security.rate-limit.lockout.max-known-ips=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
