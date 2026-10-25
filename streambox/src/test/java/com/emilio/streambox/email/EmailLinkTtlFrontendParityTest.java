package com.emilio.streambox.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

/**
 * Contrato entre backend y frontend: la vida <b>por defecto</b> de los enlaces
 * del correo ({@code streambox.email.password-reset-ttl} y
 * {@code streambox.email.verification-ttl}) coincide con las constantes que el
 * frontend copia a mano para sus textos ({@code RESET_LINK_TTL_HOURS} en
 * {@code frontend/src/lib/passwordReset.ts} y
 * {@code VERIFICATION_LINK_TTL_HOURS} en {@code frontend/src/lib/emailVerification.ts}).
 *
 * <p>
 * <b>Por qué.</b> Los dos lados lo dicen en un comentario («si se cambia allí,
 * hay que cambiarla aquí también o los textos mentirán»), pero un comentario no
 * rompe ningún build. Si alguien cambia el valor por defecto del backend y no
 * el del frontend, «Revisa tu correo» y «El enlace no es válido o ha caducado»
 * dirían una duración distinta de la del correo. Este test falla en ese caso.
 * </p>
 *
 * <p>
 * Solo protege el valor <b>por defecto</b>: si un despliegue cambia la variable
 * de entorno, el frontend no se entera (ver el informe de QA). Los archivos se
 * leen de la carpeta del frontend del mismo repositorio (Maven se ejecuta desde
 * {@code streambox/}), como {@code YouTubeUrlParityContractTest}.
 * </p>
 */
class EmailLinkTtlFrontendParityTest {

    private static final Path FRONTEND_LIB = Path.of("..", "frontend", "src", "lib");

    @Configuration
    @EnableConfigurationProperties(EmailProperties.class)
    static class TestConfig {
    }

    /** Sin las variables de entorno ni las propiedades del sistema de quien ejecuta: solo los valores por defecto. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(TestConfig.class);

    /** «He olvidado mi contraseña»: 1 h en el backend y {@code RESET_LINK_TTL_HOURS} en el frontend. */
    @Test
    void laVidaDelEnlaceDeRestablecimientoCoincideConLaDelFrontend() throws IOException {
        long frontendHours = hoursIn("passwordReset.ts", "RESET_LINK_TTL_HOURS");

        runner.run(context -> assertThat(context.getBean(EmailProperties.class).passwordResetTtl())
                .isEqualTo(Duration.ofHours(frontendHours)));
    }

    /** Confirmación del correo: 24 h en el backend y {@code VERIFICATION_LINK_TTL_HOURS} en el frontend. */
    @Test
    void laVidaDelEnlaceDeVerificacionCoincideConLaDelFrontend() throws IOException {
        long frontendHours = hoursIn("emailVerification.ts", "VERIFICATION_LINK_TTL_HOURS");

        runner.run(context -> assertThat(context.getBean(EmailProperties.class).verificationTtl())
                .isEqualTo(Duration.ofHours(frontendHours)));
    }

    /** Lee {@code export const <constant> = <n>;} de un archivo de {@code frontend/src/lib}. */
    private static long hoursIn(String file, String constant) throws IOException {
        Path path = FRONTEND_LIB.resolve(file);
        assertThat(path).as("No se encuentra " + path.toAbsolutePath()).isRegularFile();
        String source = Files.readString(path, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("export const " + constant + "\\s*=\\s*(\\d+)\\s*;").matcher(source);
        assertThat(matcher.find()).as("No se encuentra la constante " + constant + " en " + file).isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
