package com.emilio.streambox.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import com.emilio.streambox.controller.E2eEmailInboxController;

/**
 * Qué emisor de correo elige {@link EmailSenderConfig} en cada perfil, y que
 * el arranque falla donde debe:
 *
 * <ul>
 *   <li>{@code prod} sin {@code spring.mail.host} → <b>no arranca</b> (en
 *       producción nadie lee la consola: los usuarios se registrarían y nunca
 *       recibirían el enlace, y el log tendría tokens válidos);</li>
 *   <li>{@code prod} con SMTP → {@link SmtpEmailSender};</li>
 *   <li>desarrollo (sin perfil) sin SMTP → {@link LoggingEmailSender};</li>
 *   <li>{@code test} → siempre {@link MemoryEmailSender}, aunque haya SMTP
 *       configurado;</li>
 *   <li>{@code e2e} → {@link MemoryEmailSender}, y no arranca con SMTP o con
 *       una base de datos que no sea H2 en memoria (señales de un despliegue
 *       real con el perfil activado por error);</li>
 *   <li>{@code e2e} o {@code test} junto con {@code prod} → no arranca (la
 *       bandeja dejaría leer los enlaces de cualquier usuario);</li>
 *   <li>la bandeja HTTP ({@link E2eEmailInboxController}) solo existe con
 *       {@code e2e}.</li>
 * </ul>
 *
 * <p>
 * Contexto mínimo con {@link ApplicationContextRunner}, sin las variables de
 * entorno ni las propiedades de la JVM (como {@code JwtPropertiesValidationTest}):
 * un {@code SPRING_MAIL_HOST} o un {@code SPRING_PROFILES_ACTIVE} en la terminal
 * de quien ejecuta la suite no debe cambiar el resultado. El perfil se activa
 * con la propiedad {@code spring.profiles.active}.
 * </p>
 */
class EmailSenderConfigTest {

    /** URL de H2 en memoria, como la del E2E ({@code frontend/playwright.config.ts}). */
    private static final String H2_MEMORY_URL =
            "spring.datasource.url=jdbc:h2:mem:streambox_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmailProperties.class)
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(ClockConfig.class, EmailSenderConfig.class, E2eEmailInboxController.class);

    @Test
    void enProdSinServidorSmtpLaAplicacionNoArranca() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context ->
                assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining(EmailSenderConfig.PROD_WITHOUT_SMTP_MESSAGE));
    }

    @Test
    void enProdConServidorSmtpSeUsaSmtp() {
        runner.withPropertyValues("spring.profiles.active=prod", "spring.mail.host=smtp.ejemplo.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
                    assertThat(context).doesNotHaveBean(E2eEmailInboxController.class);
                });
    }

    @Test
    void enDesarrolloSinServidorSmtpLosCorreosVanAlLog() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(LoggingEmailSender.class);
            assertThat(context).doesNotHaveBean(MemoryEmailSender.class);
            assertThat(context).doesNotHaveBean(E2eEmailInboxController.class);
        });
    }

    @Test
    void enDesarrolloConServidorSmtpSeUsaSmtp() {
        runner.withPropertyValues("spring.mail.host=localhost", "spring.mail.port=1025").run(context ->
                assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class));
    }

    /** Con {@code test}, la bandeja en memoria aunque haya SMTP, y sin la bandeja HTTP. */
    @Test
    void enTestSiempreLaBandejaEnMemoriaYSinEndpoint() {
        runner.withPropertyValues("spring.profiles.active=test", "spring.mail.host=smtp.ejemplo.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(MemoryEmailSender.class);
                    assertThat(context).doesNotHaveBean(E2eEmailInboxController.class);
                });
    }

    /** Con {@code e2e} (H2 en memoria y sin SMTP, como el E2E), la bandeja en memoria y su endpoint. */
    @Test
    void enE2eLaBandejaEnMemoriaYSuEndpoint() {
        runner.withPropertyValues("spring.profiles.active=e2e", H2_MEMORY_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(MemoryEmailSender.class);
                    assertThat(context).hasSingleBean(E2eEmailInboxController.class);
                });
    }

    /**
     * I2: {@code e2e} activado por error en un despliegue sin {@code prod}. Un
     * servidor SMTP es una señal de despliegue real: no arranca. Antes
     * arrancaba y dejaba a cualquier ADMIN leer los enlaces de todos.
     */
    @Test
    void e2eConServidorSmtpNoArranca() {
        runner.withPropertyValues("spring.profiles.active=e2e", H2_MEMORY_URL, "spring.mail.host=smtp.ejemplo.test")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining(EmailSenderConfig.E2E_WITH_SMTP_MESSAGE));
    }

    /** Un {@code SPRING_MAIL_HOST=} vacío (para anular uno heredado) no cuenta como SMTP. */
    @Test
    void e2eConElServidorSmtpVacioArranca() {
        runner.withPropertyValues("spring.profiles.active=e2e", H2_MEMORY_URL, "spring.mail.host=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * I2: una base de datos que no es H2 en memoria (PostgreSQL, H2 en
     * archivo) o ninguna URL es otra señal de despliegue real: no arranca.
     */
    @ParameterizedTest
    @ValueSource(strings = { "spring.datasource.url=jdbc:postgresql://db:5432/streambox",
            "spring.datasource.url=jdbc:h2:file:./datos/streambox", "spring.datasource.url=" })
    void e2eSinH2EnMemoriaNoArranca(String datasource) {
        runner.withPropertyValues("spring.profiles.active=e2e", datasource)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining(EmailSenderConfig.E2E_WITHOUT_MEMORY_DB_MESSAGE));
    }

    @Test
    void e2eSinUrlDeBaseDeDatosNoArranca() {
        runner.withPropertyValues("spring.profiles.active=e2e")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining(EmailSenderConfig.E2E_WITHOUT_MEMORY_DB_MESSAGE));
    }

    /** Con {@code test}, la guarda de {@code e2e} no se aplica (la suite no define SMTP, pero podría heredarlo). */
    @Test
    void testSinE2eNoMiraLaBaseDeDatos() {
        runner.withPropertyValues("spring.profiles.active=test", "spring.datasource.url=jdbc:postgresql://x/y")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void e2eYProdJuntosNoArrancan() {
        runner.withPropertyValues("spring.profiles.active=e2e,prod", H2_MEMORY_URL,
                        "spring.mail.host=smtp.ejemplo.test")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining(EmailSenderConfig.MEMORY_WITH_PROD_MESSAGE));
    }

    @Test
    void testYProdJuntosNoArrancan() {
        runner.withPropertyValues("spring.profiles.active=test,prod", "spring.mail.host=smtp.ejemplo.test")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining(EmailSenderConfig.MEMORY_WITH_PROD_MESSAGE));
    }
}
