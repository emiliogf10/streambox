package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Casos límite de la política de contraseñas del registro que no cubre
 * {@link RegistrationPasswordPolicyIntegrationTest} (revisión independiente de QA).
 *
 * <p>
 * Protege sobre todo tres cosas: los límites exactos de cada regla (73 bytes,
 * 4 frente a 5 caracteres distintos, datos personales de 4 caracteres), que
 * cuando fallan varias reglas el cliente reciba siempre un único mensaje y
 * el de mayor prioridad, y que ninguna contraseña rara (surrogates sueltos,
 * caracteres de control, emojis, tipos JSON incorrectos) acabe en un 500,
 * que era el fallo original con más de 72 bytes.
 * </p>
 *
 * <p>
 * Los mensajes van literales porque son el contrato con el frontend.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class RegistrationPasswordPolicyEdgeCasesIntegrationTest {

    private static final String REQUIRED = "La contraseña es obligatoria";
    private static final String LENGTH = "La contraseña debe tener entre 12 y 64 caracteres";
    private static final String BYTES =
            "La contraseña es demasiado larga: acórtala o usa menos letras acentuadas, eñes o emojis.";
    private static final String COMMON = "La contraseña es demasiado común. Elige otra más difícil de adivinar.";
    private static final String PERSONAL = "La contraseña no puede contener tu nombre de usuario ni tu email.";

    /** Sufijo único por registro para que los casos que dan 201 no choquen entre sí. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // Límite de bytes: 72 vale (ya probado), 73 no
    // ------------------------------------------------------------------

    /**
     * Un byte por encima del máximo de BCrypt con una longitud válida: 55
     * caracteres ASCII y 9 eñes son 64 caracteres y 73 bytes. Sin la regla de
     * bytes, {@code BCryptPasswordEncoder.encode} lanzaría una excepción (500).
     */
    @Test
    void setentaYTresBytesConLongitudValidaDanElMensajeDeBytes() throws Exception {
        String password = "Lince-Iberico-Donana-2026!Halcon-Peregrino-Gredos#Oso-P".substring(0, 55) + "ñ".repeat(9);
        assertEquals(64, password.length());
        assertEquals(73, password.getBytes(StandardCharsets.UTF_8).length);

        register("pwd73", "pwd73@qa.test", password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.password").value(BYTES));
    }

    // ------------------------------------------------------------------
    // Caracteres distintos: 4 es trivial, 5 no
    // ------------------------------------------------------------------

    @Test
    void cuatroCaracteresDistintosEsTrivialYCincoNo() throws Exception {
        register("distinct4", "distinct4@qa.test", "wxyzwxyzwxyz")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(COMMON));

        register("distinct5", "distinct5@qa.test", "vwxyzvwxyzvw")
                .andExpect(status().isCreated());
    }

    /** Las mayúsculas no cuentan como caracteres distintos: "WxYz" son 4, no 8. */
    @Test
    void lasMayusculasNoAumentanLosCaracteresDistintos() throws Exception {
        register("distinctcase", "distinctcase@qa.test", "WxYzwXyZWXYZ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(COMMON));
    }

    // ------------------------------------------------------------------
    // Datos personales
    // ------------------------------------------------------------------

    /** El usuario se compara sin distinguir mayúsculas también cuando las lleva el propio usuario. */
    @Test
    void unUsuarioConMayusculasSeDetectaEnLaContrasenaEnMinusculas() throws Exception {
        register("LuciaDev", "otra.persona.qa@qa.test", "mi-casa-luciadev-de-2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(PERSONAL));
    }

    /** La parte local del email de exactamente 4 caracteres ya se comprueba (límite inclusivo). */
    @Test
    void unaParteLocalDeExactamente4CaracteresSiSeComprueba() throws Exception {
        register("pwdlocal4", "leon@qa.test", "Montes-de-LEON-2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(PERSONAL));
    }

    /**
     * Con 3 caracteres ya no se comprueba: antes la usuaria {@code ana} no
     * podía registrarse con "mañana iremos al cine" porque "ana" aparece
     * dentro de "mañana" (falso positivo).
     */
    @Test
    void unUsuarioYUnaParteLocalDe3CaracteresYaNoSeComprueban() throws Exception {
        register("ana", "ana@qa.test", "mañana iremos al cine")
                .andExpect(status().isCreated());
    }

    /**
     * Regresión: la lista de comunes se comparaba tras {@code trim()}, que no
     * quita los espacios Unicode, así que {@code password1234} rodeada de
     * espacios de no separación, de ancho cero o ideográficos se aceptaba.
     */
    @Test
    void unaContrasenaComunRodeadaDeEspaciosUnicodeSeRechaza() throws Exception {
        String nbsp = Character.toString(0x00A0);
        String zeroWidth = Character.toString(0x200B);
        String ideographic = Character.toString(0x3000);

        register("unicode1", "unicode1@qa.test", nbsp + nbsp + "password1234" + nbsp + nbsp)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(COMMON));
        register("unicode2", "unicode2@qa.test", zeroWidth + "Password1234" + ideographic)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(COMMON));
    }

    /**
     * Un usuario de 2 caracteres no es válido (mínimo 3), y la regla de datos
     * personales no debe sumarle a la contraseña un error que no tiene: solo
     * se informa del usuario.
     */
    @Test
    void unUsuarioDe2CaracteresNoProvocaErrorDeDatosPersonales() throws Exception {
        register("ab", "persona.qa2@qa.test", "Cabra-Montes-77")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.username").exists())
                .andExpect(jsonPath("$.validationErrors.password").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Prioridad cuando fallan varias reglas
    // ------------------------------------------------------------------

    /** Corta y común: manda la longitud. */
    @Test
    void cortaYComunDaSoloLaLongitud() throws Exception {
        register("prio1", "prio1@qa.test", "password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(LENGTH));
    }

    /** Demasiados bytes y trivial (un solo carácter distinto): mandan los bytes. */
    @Test
    void muchosBytesYTrivialDaSoloLosBytes() throws Exception {
        String password = "ñ".repeat(37);
        assertEquals(74, password.getBytes(StandardCharsets.UTF_8).length);

        register("prio2", "prio2@qa.test", password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(BYTES));
    }

    /** Larga (65) y con el usuario: manda la longitud. */
    @Test
    void largaYConElUsuarioDaSoloLaLongitud() throws Exception {
        String password = ("prio3-" + "Lince-Iberico-Donana-2026!Halcon-Peregrino-Gredos#Oso-Pardo-Somiedo")
                .substring(0, 65);

        register("prio3", "prio3@qa.test", password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(LENGTH));
    }

    // ------------------------------------------------------------------
    // Ausente, nula o de otro tipo: nunca 500
    // ------------------------------------------------------------------

    @Test
    void unaContrasenaNullExplicitaEsObligatoria() throws Exception {
        rawRegister("{\"username\":\"pwdnul\",\"email\":\"pwdnul@qa.test\",\"password\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.password").value(REQUIRED));
    }

    /** Un array o un objeto en lugar de texto es un JSON que no encaja: 400 MALFORMED_REQUEST, nunca 500. */
    @ParameterizedTest
    @ValueSource(strings = { "[\"Rio-Tajo-482\"]", "{\"valor\":\"Rio-Tajo-482\"}" })
    void unaContrasenaQueNoEsTextoDa400MalformedRequest(String password) throws Exception {
        rawRegister("{\"username\":\"pwdtype\",\"email\":\"pwdtype@qa.test\",\"password\":" + password + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.validationErrors").doesNotExist());
    }

    /**
     * Contraseñas con caracteres que suelen romper el cálculo de longitudes o
     * de bytes. Se envían con escapes JSON ({@code \\uXXXX}) para que lleguen
     * al servidor exactamente así (incluido un surrogate suelto, que no se
     * puede codificar en UTF-8 y Java sustituye por {@code ?}, igual en la
     * política que en BCrypt). Pueden acabar en 201 o en 400, pero nunca en
     * un 5xx, y si es 400 el mensaje es uno de los del contrato.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Rio-Tajo-482\\ud83d",                              // surrogate alto suelto al final
            "\\udc00Rio-Tajo-482",                              // surrogate bajo suelto al principio
            "Rio\\u0000Tajo\\u0000482",                         // caracteres NUL
            "Rio-Tajo-482\\u202e\\u200b\\u200d",                // inversión de texto y anchos cero
            "Rio-Tajo-482-\\u0301\\u0301\\u0301",               // tildes combinantes sueltas
            "\\ud83d\\ude00\\ud83c\\udfac\\ud83c\\udf7f\\ud83c\\udfa5\\ud83d\\ude00\\ud83c\\udfac", // 6 emojis: 12 unidades, 24 bytes
            "\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00"
                    + "\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00"
                    + "\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00\\ud83d\\ude00", // 19 iguales
            "\\t\\t\\tRio-Tajo-482\\n\\r",                     // controles en los extremos
            "\\u00a0\\u00a0password1234\\u00a0\\u00a0"         // espacios de no separación
    })
    void ningunaContrasenaRaraProvocaUn500(String escapedPassword) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        MockHttpServletResponse response = rawRegister("{\"username\":\"raro" + n + "\",\"email\":\"raro" + n
                + "@qa.test\",\"password\":\"" + escapedPassword + "\"}")
                .andReturn().getResponse();

        int status = response.getStatus();
        assertTrue(status == 201 || status == 400,
                () -> "Estado inesperado " + status + ": " + contentOf(response));
        if (status == 400) {
            JsonNode body = objectMapper.readTree(contentOf(response));
            assertEquals("VALIDATION_ERROR", body.path("code").asText());
            String message = body.path("validationErrors").path("password").asText();
            assertTrue(java.util.List.of(LENGTH, BYTES, COMMON, PERSONAL).contains(message), message);
        } else {
            assertFalse(contentOf(response).contains("password"), "La respuesta no debe incluir la contraseña");
        }
    }

    // ------------------------------------------------------------------
    // El login no aplica la política
    // ------------------------------------------------------------------

    /**
     * El login solo exige que la contraseña no esté vacía: con 1 carácter
     * debe comprobar las credenciales (401) y no rechazarla por longitud
     * (400), o una cuenta antigua con una contraseña corta no sabría por qué
     * no puede entrar.
     */
    @Test
    void elLoginNoRechazaPorLongitudUnaContrasenaCorta() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "nadie-corta@qa.test", "password", "x"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.validationErrors").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions register(String username, String email, String password) throws Exception {
        return rawRegister(objectMapper.writeValueAsString(
                Map.of("username", username, "email", email, "password", password)));
    }

    private ResultActions rawRegister(String json) throws Exception {
        return mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String contentOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
