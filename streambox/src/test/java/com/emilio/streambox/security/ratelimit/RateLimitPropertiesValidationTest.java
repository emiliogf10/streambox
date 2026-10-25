package com.emilio.streambox.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

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
                    "streambox.security.rate-limit.email-verification.max-requests=20",
                    "streambox.security.rate-limit.email-verification.window=15m",
                    "streambox.security.rate-limit.email-verification-resend.max-requests=5",
                    "streambox.security.rate-limit.email-verification-resend.window=1h",
                    "streambox.security.rate-limit.password-reset.max-requests=5",
                    "streambox.security.rate-limit.password-reset.window=1h",
                    "streambox.security.rate-limit.password-reset-confirm.max-requests=20",
                    "streambox.security.rate-limit.password-reset-confirm.window=15m",
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

    /** Los límites de la verificación del correo se enlazan con sus nombres. */
    @Test
    void enlazaLosLimitesDeLaVerificacionDelCorreo() {
        runner.withPropertyValues("streambox.security.rate-limit.max-keys=100000").run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.emailVerification().maxRequests()).isEqualTo(20);
            assertThat(properties.emailVerification().window()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.emailVerificationResend().maxRequests()).isEqualTo(5);
            assertThat(properties.emailVerificationResend().window()).isEqualTo(Duration.ofHours(1));
        });
    }

    /**
     * Son obligatorios y positivos, como los demás: sin ellos (o a 0) la
     * aplicación no arranca, en vez de dejar las rutas sin límite.
     */
    @Test
    void unLimiteDeVerificacionDelCorreoNoPositivoImpideArrancar() {
        runner.withPropertyValues(
                        "streambox.security.rate-limit.max-keys=100000",
                        "streambox.security.rate-limit.email-verification.max-requests=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "streambox.security.rate-limit.max-keys=100000",
                        "streambox.security.rate-limit.email-verification-resend.max-requests=0")
                .run(context -> assertThat(context).hasFailed());
    }

    /** Los límites del restablecimiento de la contraseña se enlazan con sus nombres. */
    @Test
    void enlazaLosLimitesDelRestablecimientoDeLaContrasena() {
        runner.withPropertyValues("streambox.security.rate-limit.max-keys=100000").run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.passwordReset().maxRequests()).isEqualTo(5);
            assertThat(properties.passwordReset().window()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.passwordResetConfirm().maxRequests()).isEqualTo(20);
            assertThat(properties.passwordResetConfirm().window()).isEqualTo(Duration.ofMinutes(15));
        });
    }

    /**
     * Las variables de entorno que usan el E2E ({@code frontend/playwright.config.ts})
     * y Docker se enlazan con cada límite por separado: el nombre de una
     * ({@code PASSWORD_RESET_MAX_REQUESTS}) no es prefijo ambiguo de la otra
     * ({@code PASSWORD_RESET_CONFIRM_MAX_REQUESTS}). Si se renombrara una
     * propiedad, el E2E seguiría con el límite de producción (5 por hora) y
     * fallaría con 429 sin una pista clara.
     */
    @Test
    void lasVariablesDeEntornoDelE2eEnlazanLosLimitesDelRestablecimiento() {
        runner.withPropertyValues("streambox.security.rate-limit.max-keys=100000")
                // Con el nombre estándar: Spring Boot solo aplica las reglas de
                // las variables de entorno a una fuente con ese nombre. Se
                // añade después de que el runner quite la del sistema.
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                                "STREAMBOX_SECURITY_RATE_LIMIT_PASSWORD_RESET_MAX_REQUESTS", "1001",
                                "STREAMBOX_SECURITY_RATE_LIMIT_PASSWORD_RESET_CONFIRM_MAX_REQUESTS", "1002"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);
                    assertThat(properties.passwordReset().maxRequests()).isEqualTo(1001);
                    assertThat(properties.passwordResetConfirm().maxRequests()).isEqualTo(1002);
                });
    }

    /** Obligatorios y positivos, como los demás: a 0 la aplicación no arranca. */
    @Test
    void unLimiteDelRestablecimientoNoPositivoImpideArrancar() {
        runner.withPropertyValues(
                        "streambox.security.rate-limit.max-keys=100000",
                        "streambox.security.rate-limit.password-reset.max-requests=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "streambox.security.rate-limit.max-keys=100000",
                        "streambox.security.rate-limit.password-reset-confirm.max-requests=0")
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
