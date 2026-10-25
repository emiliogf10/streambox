package com.emilio.streambox.email;

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
 * Vida del enlace de «He olvidado mi contraseña»
 * ({@code streambox.email.password-reset-ttl}): su valor por defecto, sus
 * límites y la variable de entorno con la que se cambia.
 *
 * <p>
 * La variable {@code STREAMBOX_EMAIL_PASSWORD_RESET_TTL} no aparece como
 * {@code ${...}} en {@code application.properties}: la rellena el
 * <i>relaxed binding</i> de Spring Boot. Como el README y el manual la
 * documentan con ese nombre, este test comprueba que de verdad se enlaza (si
 * alguien renombrara la propiedad, la variable dejaría de tener efecto sin
 * ningún error).
 * </p>
 */
class EmailPropertiesPasswordResetTtlTest {

    @Configuration
    @EnableConfigurationProperties(EmailProperties.class)
    static class TestConfig {
    }

    /** Contexto hermético: sin las variables de entorno ni las propiedades de la JVM reales. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(TestConfig.class);

    @Test
    void porDefectoElEnlaceDuraUnaHora() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmailProperties.class).passwordResetTtl()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void laVariableDeEntornoDocumentadaCambiaLaVidaDelEnlace() {
        runner
                // Con el nombre estándar: Spring Boot solo aplica las reglas de
                // las variables de entorno a una fuente con ese nombre.
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.<String, Object>of("STREAMBOX_EMAIL_PASSWORD_RESET_TTL", "30m"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmailProperties.class).passwordResetTtl())
                            .isEqualTo(Duration.ofMinutes(30));
                });
    }

    /** Los extremos admitidos (5 minutos y 24 horas) arrancan. */
    @Test
    void admiteDeCincoMinutosAVeinticuatroHoras() {
        runner.withPropertyValues("streambox.email.password-reset-ttl=5m")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("streambox.email.password-reset-ttl=24h")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * Fuera del rango la aplicación no arranca: menos de 5 minutos no da
     * tiempo a que llegue el correo, y más de un día deja demasiado tiempo
     * vivo un enlace que da acceso a la cuenta.
     */
    @Test
    void fueraDelRangoNoArranca() {
        runner.withPropertyValues("streambox.email.password-reset-ttl=4m")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("streambox.email.password-reset-ttl"));
        runner.withPropertyValues("streambox.email.password-reset-ttl=25h")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("streambox.email.password-reset-ttl"));
    }
}
