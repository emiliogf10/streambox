package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Documentación OpenAPI ({@code /v3/api-docs}) de los cambios de login y
 * registro (revisión independiente de QA).
 *
 * <p>
 * La documentación es parte del contrato: el frontend (u otro cliente) la usa
 * para saber que el 401 del login trae {@code remainingAttempts}, que el 429
 * puede ser {@code ACCOUNT_LOCKED} o {@code RATE_LIMIT_EXCEEDED}, qué reglas
 * tiene la contraseña del registro y que el login no las aplica. Si alguien
 * borra una de estas descripciones o cambia las restricciones del DTO sin
 * querer, estos tests lo detectan.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthOpenApiDocumentationIntegrationTest {

    private static final String LOGIN = "$.paths['/api/auth/login'].post.responses";
    private static final String REGISTER = "$.paths['/api/users'].post.responses";
    private static final String SCHEMAS = "$.components.schemas";

    @Autowired private MockMvc mockMvc;

    @Test
    void elLoginDocumentaRemainingAttemptsYLosDosCodigosDel429() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(LOGIN + "['401'].description").value(allOf(
                        containsString("INVALID_CREDENTIALS"),
                        containsString("remainingAttempts"))))
                .andExpect(jsonPath(LOGIN + "['429'].description").value(allOf(
                        containsString("ACCOUNT_LOCKED"),
                        containsString("RATE_LIMIT_EXCEEDED"),
                        containsString("Retry-After"))))
                .andExpect(jsonPath(LOGIN + "['400'].description").exists());
    }

    @Test
    void elRegistroDocumentaLaPoliticaDeContrasenas() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(REGISTER + "['400'].description").value(allOf(
                        containsString("12 y 64"),
                        containsString("72 bytes"),
                        containsString("común"))))
                .andExpect(jsonPath(SCHEMAS + ".CreateUserRequest.required").value(hasItem("password")))
                .andExpect(jsonPath(SCHEMAS + ".CreateUserRequest.properties.password.minLength").value(12))
                .andExpect(jsonPath(SCHEMAS + ".CreateUserRequest.properties.password.maxLength").value(64))
                .andExpect(jsonPath(SCHEMAS + ".CreateUserRequest.properties.password.description").value(allOf(
                        containsString("72 bytes"),
                        containsString("nombre de usuario"))));
    }

    /**
     * El login no aplica la política del registro: su contraseña no debe
     * documentar mínimo de 12 ni máximo de 64 (las cuentas antiguas tienen
     * contraseñas de 8 caracteres). Su único máximo es el tope de 1024 contra
     * cuerpos enormes, que no es una regla de la política.
     */
    @Test
    void elLoginNoDocumentaLaLongitudDelRegistro() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SCHEMAS + ".LoginRequest.properties.password.minLength")
                        .value(lessThanOrEqualTo(1)))
                .andExpect(jsonPath(SCHEMAS + ".LoginRequest.properties.password.maxLength").value(1024));
    }

    @Test
    void lasUrlsDePeliculaDocumentanSuLongitudMaxima() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SCHEMAS + ".MovieRequest.properties.imageUrl.maxLength").value(500))
                .andExpect(jsonPath(SCHEMAS + ".MovieRequest.properties.videoUrl.maxLength").value(500))
                .andExpect(jsonPath(SCHEMAS + ".MovieRequest.required").value(allOf(
                        hasItem("imageUrl"), hasItem("videoUrl"))));
    }
}
