package com.emilio.streambox.email;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

/**
 * Elige el {@link EmailSender} de cada entorno y crea el
 * {@link EmailDispatcher} con su ejecutor.
 *
 * <table>
 *   <caption>Qué emisor se usa</caption>
 *   <tr><th>Perfil</th><th>Con {@code spring.mail.host}</th><th>Sin {@code spring.mail.host} (o vacío)</th></tr>
 *   <tr><td>{@code test}, {@code e2e}</td><td colspan="2">{@link MemoryEmailSender} (bandeja en memoria)</td></tr>
 *   <tr><td>{@code prod}</td><td>{@link SmtpEmailSender}</td><td><b>el arranque falla</b></td></tr>
 *   <tr><td>otros ({@code dev})</td><td>{@link SmtpEmailSender}</td><td>{@link LoggingEmailSender}</td></tr>
 * </table>
 *
 * <p>
 * <b>Por qué falla en {@code prod} sin SMTP</b> en lugar de escribir en el
 * log como en desarrollo: en producción nadie lee la consola para copiar el
 * enlace, así que los usuarios se registrarían y nunca podrían entrar, sin
 * ningún error visible; y el log de producción tendría tokens válidos. Mejor
 * que no arranque y lo diga.
 * </p>
 *
 * <p>
 * <b>Por qué {@code test} y {@code e2e} usan siempre la bandeja</b>: los tests
 * leen el enlace de ella. Con {@code test}, aunque haya SMTP (un
 * {@code SPRING_MAIL_HOST} en la terminal de quien ejecuta la suite no debe
 * mandar correos de verdad). Con {@code e2e}, un SMTP impide arrancar, igual
 * que una base de datos que no sea H2 en memoria: son señales de que el
 * perfil se ha activado por error en un despliegue
 * ({@link #checkE2eEnvironment}).
 * </p>
 *
 * <p>
 * El {@code JavaMailSender} lo crea Spring Boot (autoconfiguración de
 * {@code spring-boot-starter-mail}) cuando {@code spring.mail.host} está
 * definido: por eso se pide con un {@link ObjectProvider}, que no falla si no
 * existe.
 * </p>
 *
 * <p>
 * <b>Un {@code spring.mail.host} vacío o en blanco cuenta como «sin SMTP»</b>
 * en todos los perfiles (igual que en {@link #checkE2eEnvironment}). La
 * autoconfiguración de Spring Boot solo mira si la propiedad <em>existe</em>:
 * con {@code SPRING_MAIL_HOST=} (lo que dejan muchos {@code .env} y
 * {@code docker compose} cuando la variable no tiene valor) crea un
 * {@code JavaMailSender} sin servidor. Usarlo hacía que {@code prod} arrancara
 * y que cada envío fallara con un simple WARN: nadie podría confirmar su
 * cuenta, justo lo que la guarda de {@code prod} quiere evitar.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class EmailSenderConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailSenderConfig.class);

    /** Mensaje del arranque fallido en {@code prod} sin SMTP. */
    static final String PROD_WITHOUT_SMTP_MESSAGE = "El perfil prod necesita un servidor SMTP para enviar los "
            + "correos de verificación: define SPRING_MAIL_HOST y SPRING_MAIL_PORT (y SPRING_MAIL_USERNAME y "
            + "SPRING_MAIL_PASSWORD si el servidor pide autenticación). Con docker compose lo hace el servicio "
            + "mailpit.";

    /** Mensaje del arranque fallido con la bandeja en memoria y {@code prod} a la vez. */
    static final String MEMORY_WITH_PROD_MESSAGE = "Los perfiles e2e/test no se pueden activar junto con prod: "
            + "la bandeja de correo en memoria (y GET /api/admin/e2e/emails) dejaría leer los enlaces de "
            + "verificación de cualquier usuario.";

    /** Mensaje del arranque fallido con el perfil {@code e2e} y un servidor SMTP. */
    static final String E2E_WITH_SMTP_MESSAGE = "El perfil e2e no admite un servidor SMTP (spring.mail.host / "
            + "SPRING_MAIL_HOST): es solo para los tests de punta a punta, con la bandeja en memoria. Si ves esto "
            + "en un despliegue, quita e2e de SPRING_PROFILES_ACTIVE.";

    /** Mensaje del arranque fallido con el perfil {@code e2e} y una base de datos que no es H2 en memoria. */
    static final String E2E_WITHOUT_MEMORY_DB_MESSAGE = "El perfil e2e solo arranca con una base de datos H2 en "
            + "memoria (spring.datasource.url = jdbc:h2:mem:...): es solo para los tests de punta a punta. Si ves "
            + "esto en un despliegue, quita e2e de SPRING_PROFILES_ACTIVE.";

    /** Prefijo de la URL de una base H2 en memoria, la única que admite el perfil {@code e2e}. */
    static final String H2_MEMORY_URL_PREFIX = "jdbc:h2:mem:";

    /** Hilos del ejecutor de correos: el SMTP es lento, pero el volumen es bajo. */
    private static final int EMAIL_THREADS = 2;

    /**
     * Correos que pueden esperar en cola. Acotada a propósito: con el SMTP
     * caído, una cola sin límite acumularía correos (con sus tokens) en memoria
     * hasta agotarla.
     */
    private static final int EMAIL_QUEUE_CAPACITY = 500;

    /**
     * Bandeja en memoria de los perfiles {@code test} y {@code e2e}.
     *
     * @param clock       reloj de la aplicación
     * @param environment entorno, para comprobar los perfiles y la configuración
     * @return la bandeja
     * @throws IllegalStateException si el perfil {@code prod} también está
     *         activo, o si {@code e2e} no está en un entorno de pruebas (ver
     *         {@link #checkE2eEnvironment})
     */
    @Bean
    @Profile({ "test", "e2e" })
    MemoryEmailSender memoryEmailSender(Clock clock, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(MEMORY_WITH_PROD_MESSAGE);
        }
        if (environment.acceptsProfiles(Profiles.of("e2e"))) {
            checkE2eEnvironment(environment);
        }
        return new MemoryEmailSender(clock);
    }

    /**
     * Comprueba que el perfil {@code e2e} se usa donde debe: en los tests de
     * punta a punta, con H2 en memoria y sin SMTP.
     *
     * <p>
     * <b>Por qué.</b> Con {@code e2e}, cualquier ADMIN puede leer en
     * {@code GET /api/admin/e2e/emails} los enlaces de verificación de todos
     * los usuarios. Que {@code e2e} y {@code prod} no puedan ir juntos ya
     * estaba cubierto, pero un despliegue que activara {@code e2e} <em>sin</em>
     * {@code prod} (un error al copiar {@code SPRING_PROFILES_ACTIVE}) no lo
     * detectaba nada: arrancaba con una base de datos real y esa bandeja. Un
     * despliegue real se reconoce por lo que el E2E nunca tiene: un servidor
     * SMTP o una base de datos que no es H2 en memoria. Si aparece cualquiera
     * de los dos, no arranca. Y siempre deja un WARN en el log, para que se vea
     * qué perfil está activo.
     * </p>
     *
     * <p>
     * Un {@code spring.mail.host} vacío cuenta como no definido (así se puede
     * anular uno heredado de la terminal con {@code SPRING_MAIL_HOST=}).
     * </p>
     *
     * @param environment entorno de la aplicación
     * @throws IllegalStateException si hay SMTP o la base de datos no es H2 en memoria
     */
    static void checkE2eEnvironment(Environment environment) {
        if (StringUtils.hasText(environment.getProperty("spring.mail.host"))) {
            throw new IllegalStateException(E2E_WITH_SMTP_MESSAGE);
        }
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith(H2_MEMORY_URL_PREFIX)) {
            throw new IllegalStateException(E2E_WITHOUT_MEMORY_DB_MESSAGE);
        }
        LOGGER.warn("Perfil e2e activo: los correos van a una bandeja en memoria que cualquier ADMIN puede leer "
                + "en GET /api/admin/e2e/emails. Solo para los tests de punta a punta; nunca en un despliegue.");
    }

    /**
     * Emisor del resto de perfiles: SMTP si está configurado; si no, el log en
     * desarrollo o un arranque fallido en {@code prod}.
     *
     * <p>
     * «Configurado» es {@code spring.mail.host} con texto
     * ({@link StringUtils#hasText}), no solo que exista el
     * {@code JavaMailSender}: con la propiedad vacía Spring Boot lo crea igual,
     * sin servidor (ver la clase).
     * </p>
     *
     * @param mailSender  cliente SMTP de Spring Boot, si existe
     * @param properties  propiedades del correo (remitente)
     * @param environment entorno, para saber si es {@code prod} y leer {@code spring.mail.host}
     * @return el emisor
     * @throws IllegalStateException en {@code prod} sin {@code spring.mail.host}
     *         (o con él vacío o en blanco)
     */
    @Bean
    @Profile("!test & !e2e")
    EmailSender emailSender(ObjectProvider<JavaMailSender> mailSender, EmailProperties properties,
            Environment environment) {
        JavaMailSender smtp = StringUtils.hasText(environment.getProperty("spring.mail.host"))
                ? mailSender.getIfAvailable()
                : null;
        if (smtp != null) {
            return new SmtpEmailSender(smtp, properties.from());
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(PROD_WITHOUT_SMTP_MESSAGE);
        }
        LOGGER.warn("Sin servidor SMTP (spring.mail.host): los correos, con sus enlaces, se escriben en el log. "
                + "Solo para desarrollo.");
        return new LoggingEmailSender();
    }

    /**
     * Despachador que usan los servicios, con su propio ejecutor.
     *
     * <p>
     * El ejecutor no se registra como bean a propósito: Spring Boot solo crea
     * su ejecutor general ({@code applicationTaskExecutor}, el de las peticiones
     * asíncronas de Spring MVC) si no hay ya un bean {@link Executor}, y uno
     * dedicado al correo no debe sustituirlo. Spring llama a
     * {@link EmailDispatcher#close()} al apagar (método {@code close} inferido),
     * que espera a los envíos en curso.
     * </p>
     *
     * @param sender     emisor del entorno
     * @param properties propiedades del correo ({@code async})
     * @return el despachador
     */
    @Bean
    EmailDispatcher emailDispatcher(EmailSender sender, EmailProperties properties) {
        return new EmailDispatcher(sender, properties.async() ? newEmailExecutor() : Runnable::run);
    }

    /**
     * Ejecutor de los envíos: {@value #EMAIL_THREADS} hilos y una cola de
     * {@value #EMAIL_QUEUE_CAPACITY}. Si la cola se llena, rechaza el envío
     * (lo registra {@link EmailDispatcher}). Al apagar, espera hasta 10 s a que
     * terminen los envíos en curso, para no perder los de los últimos
     * registros.
     */
    private static ThreadPoolTaskExecutor newEmailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("email-");
        executor.setCorePoolSize(EMAIL_THREADS);
        executor.setMaxPoolSize(EMAIL_THREADS);
        executor.setQueueCapacity(EMAIL_QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
