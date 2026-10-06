package com.emilio.streambox.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/**
 * Comprueba que la aplicación se niega a arrancar con una configuración JWT
 * insegura, en lugar de fallar más tarde al firmar el primer token, y que el
 * error <strong>nunca</strong> muestra el secreto rechazado.
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
 *
 * <h2>Fuga del secreto en el log</h2>
 * <p>
 * Con {@code @Size} el informe de arranque fallido de Spring Boot imprimía
 * {@code Value: "<secreto>"}. Los tests de fuga usan un secreto corto
 * reconocible ({@link #LEAKED_SECRET}) y buscan ese texto en la traza completa
 * de la excepción (con todas sus causas encadenadas) y en todo lo que el
 * arranque escribe por consola, que es lo que acabaría en el log del
 * contenedor.
 * </p>
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtPropertiesValidationTest {

    private static final String VALID_SECRET = "a".repeat(32);

    /** Secreto demasiado corto (20 caracteres) y fácil de buscar en el log. */
    private static final String LEAKED_SECRET = "secreto-filtrado-123";

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

    /** Traza completa (mensaje, causas encadenadas y suprimidas) como texto. */
    private static String fullStackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

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
    void secretoDeExactamente32CaracteresEsValido() {
        assertThat(new JwtProperties(VALID_SECRET, 24).secret()).isEqualTo(VALID_SECRET);
    }

    @Test
    void secretoDemasiadoCortoImpideArrancar() {
        runner.withPropertyValues("jwt.secret=" + "a".repeat(31)).run(context ->
                assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("al menos 32 caracteres")
                        .hasStackTraceContaining("JWT_SECRET")
                        .hasStackTraceContaining("openssl rand -base64 48"));
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
                assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("jwt.secret es obligatorio"));
    }

    /**
     * Si la variable de entorno no existe, Spring Boot deja el marcador
     * literal. Aquí es largo a propósito (más de 32 caracteres): sin la
     * comprobación del marcador se aceptaría como clave de firma un texto
     * público.
     */
    @Test
    void marcadorSinResolverCuentaComoSecretoAusente() {
        runner.withPropertyValues("jwt.secret=${STREAMBOX_VARIABLE_QUE_NO_EXISTE_EN_NINGUN_ENTORNO}")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("jwt.secret es obligatorio"));
    }

    @Test
    void secretoSoloConEspaciosImpideArrancar() {
        assertThatThrownBy(() -> new JwtProperties(" ".repeat(40), 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret es obligatorio");
    }

    @Test
    void duracionCeroONegativaImpideArrancar() {
        runner.withPropertyValues("jwt.secret=" + VALID_SECRET, "jwt.expiration-hours=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("jwt.expiration-hours"));
    }

    /**
     * La traza completa del fallo de arranque, con todas las causas
     * encadenadas, no contiene el secreto rechazado (ni un trozo de él).
     */
    @Test
    void elFalloDelContextoNoContieneElSecretoNiEnLasCausas() {
        runner.withPropertyValues("jwt.secret=" + LEAKED_SECRET).run(context -> {
            assertThat(context).hasFailed();
            String trace = fullStackTrace(context.getStartupFailure());

            assertThat(trace).contains("al menos 32 caracteres")
                    .doesNotContain(LEAKED_SECRET)
                    .doesNotContain("filtrado");
        });
    }

    /**
     * Arranque real con {@link SpringApplication}: es lo que ocurre en el
     * contenedor. Se comprueba que el arranque falla y que nada de lo escrito
     * por consola (el informe «APPLICATION FAILED TO START» de los
     * {@code FailureAnalyzer}, o la traza de «Application run failed» si
     * ninguno lo analiza) contiene el secreto.
     *
     * <p>
     * El secreto llega como argumento de línea de comandos, que tiene más
     * prioridad que la variable {@code JWT_SECRET} de la máquina.
     * {@code spring.config.name} apunta a un nombre sin fichero para no cargar
     * {@code application.properties} (ni el {@code application-local.properties}
     * que importa, con la conexión de desarrollo del usuario).
     * </p>
     */
    @Test
    void elArranqueRealFallaSinEscribirElSecretoEnElLog(CapturedOutput output) {
        SpringApplication application = new SpringApplication(TestConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> application.run(
                "--spring.config.name=jwt-properties-validation-test-sin-fichero",
                "--spring.main.banner-mode=off",
                "--jwt.secret=" + LEAKED_SECRET))
                .satisfies(failure -> assertThat(fullStackTrace(failure))
                        .contains("al menos 32 caracteres")
                        .doesNotContain(LEAKED_SECRET));

        // Garantiza que el informe de arranque fallido se ha escrito de verdad:
        // sin esto, la comprobación de abajo pasaría aunque no se capturase nada.
        assertThat(output.getAll()).contains("APPLICATION FAILED TO START")
                .contains("al menos 32 caracteres")
                .doesNotContain(LEAKED_SECRET)
                .doesNotContain("filtrado");
    }

    @Test
    void elMensajeDelConstructorNoIncluyeElSecretoNiSuLongitud() {
        assertThatThrownBy(() -> new JwtProperties(LEAKED_SECRET, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(JwtProperties.SHORT_SECRET_MESSAGE)
                .message().doesNotContain(LEAKED_SECRET)
                .doesNotContain(String.valueOf(LEAKED_SECRET.length()));
    }

    @Test
    void toStringNoMuestraElSecreto() {
        String secret = "secreto-largo-que-no-debe-salir-en-logs-0123456789";

        assertThat(new JwtProperties(secret, 24).toString())
                .doesNotContain(secret)
                .contains("expirationHours=24");
    }
}
