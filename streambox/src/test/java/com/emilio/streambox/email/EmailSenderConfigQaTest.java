package com.emilio.streambox.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/**
 * Revisión de QA (2026-10-18) de las guardas de {@link EmailSenderConfig}: un
 * {@code SPRING_MAIL_HOST} <b>vacío o en blanco</b> en {@code prod}.
 *
 * <p>
 * <b>Por qué.</b> {@code docker compose} y muchos {@code .env} definen la
 * variable aunque sea vacía ({@code SPRING_MAIL_HOST=}). El perfil {@code e2e}
 * ya trata el vacío como «sin SMTP» ({@code EmailSenderConfigTest}). En
 * {@code prod} debe pasar lo mismo: o no arranca (sin SMTP no hay forma de
 * confirmar cuentas) o arranca con un emisor que de verdad envía. Lo que no
 * puede pasar es que arranque con un {@code JavaMailSender} sin servidor, que
 * fallaría en cada envío (solo un {@code WARN}) y dejaría a todos los usuarios
 * nuevos sin poder entrar, justo lo que la guarda de {@code prod} quiere
 * evitar.
 * </p>
 *
 * <p>
 * Mismo contexto mínimo que {@code EmailSenderConfigTest}, sin las variables
 * de entorno ni las propiedades de la JVM de quien ejecuta la suite.
 * </p>
 */
class EmailSenderConfigQaTest {

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
            .withUserConfiguration(ClockConfig.class, EmailSenderConfig.class);

    /**
     * {@code prod} con el servidor SMTP vacío o en blanco no debe arrancar con
     * un emisor SMTP sin servidor: o falla con el mensaje de «falta SMTP», o el
     * contexto no tiene un {@link SmtpEmailSender}.
     */
    @ParameterizedTest
    @ValueSource(strings = { "spring.mail.host=", "spring.mail.host=   " })
    void enProdUnServidorSmtpVacioCuentaComoSinSmtp(String host) {
        runner.withPropertyValues("spring.profiles.active=prod", host).run(context -> {
            if (context.getStartupFailure() == null) {
                assertThat(context.getBean(EmailSender.class))
                        .as("prod arrancó con SPRING_MAIL_HOST vacío y un emisor SMTP sin servidor")
                        .isNotInstanceOf(SmtpEmailSender.class);
            } else {
                assertThat(context).getFailure()
                        .hasStackTraceContaining(EmailSenderConfig.PROD_WITHOUT_SMTP_MESSAGE);
            }
        });
    }
}
