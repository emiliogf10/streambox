package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests de integración de la política de contraseñas del registro público
 * ({@code POST /api/users}).
 *
 * <p>
 * Los mensajes se escriben aquí literalmente (y no con las constantes de
 * {@code PasswordPolicy}) porque son parte del contrato con el frontend: si
 * alguien cambia un texto por descuido, el test debe romperse.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class RegistrationPasswordPolicyIntegrationTest {

    private static final String LENGTH = "La contraseña debe tener entre 12 y 64 caracteres";
    private static final String BYTES =
            "La contraseña es demasiado larga: acórtala o usa menos letras acentuadas, eñes o emojis.";
    private static final String COMMON = "La contraseña es demasiado común. Elige otra más difícil de adivinar.";
    private static final String PERSONAL = "La contraseña no puede contener tu nombre de usuario ni tu email.";

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // Longitud (12 a 64 caracteres)
    // ------------------------------------------------------------------

    @Test
    void onceCaracteresSeRechazan() throws Exception {
        register("pwd11", "pwd11@test.com", "Rio-Tajo-48")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.password").value(LENGTH));
    }

    @Test
    void doceCaracteresSeAceptan() throws Exception {
        register("pwd12", "pwd12@test.com", "Rio-Tajo-482")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void sesentaYCuatroCaracteresSeAceptan() throws Exception {
        register("pwd64", "pwd64@test.com", ascii(64)).andExpect(status().isCreated());
    }

    @Test
    void sesentaYCincoCaracteresSeRechazan() throws Exception {
        register("pwd65", "pwd65@test.com", ascii(65))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(LENGTH));
    }

    @Test
    void unaContrasenaVaciaDaElMensajeDeLongitud() throws Exception {
        register("pwdempty", "pwdempty@test.com", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(LENGTH));
    }

    @Test
    void sinContrasenaEsObligatoria() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "pwdnull", "email", "pwdnull@test.com"));
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value("La contraseña es obligatoria"));
    }

    // ------------------------------------------------------------------
    // Límite de 72 bytes de BCrypt
    // ------------------------------------------------------------------

    /**
     * Regresión: 64 caracteres pasaban la validación de longitud, pero con
     * tildes ocupan más de 72 bytes y {@code BCryptPasswordEncoder.encode}
     * lanzaba {@code IllegalArgumentException}, que acababa en un 500.
     */
    @Test
    void sesentaYCuatroCaracteresConMasDe72BytesDan400YNunca500() throws Exception {
        String password = "Ñandú-Pingüino-Cigüeña-".repeat(3).substring(0, 64);
        assertEquals(64, password.length());
        assertTrue(password.getBytes(StandardCharsets.UTF_8).length > 72);

        register("pwdbytes", "pwdbytes@test.com", password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.password").value(BYTES));
    }

    @Test
    void losEmojisCuentanSusBytes() throws Exception {
        // Cada emoji son 2 unidades UTF-16 (lo que cuenta la longitud) pero 4
        // bytes: 19 emojis y 3 letras son 41 "caracteres" y 79 bytes.
        String password = "😀🎬🍿🎥".repeat(4) + "abc😀😀😀";
        assertTrue(password.length() <= 64);
        assertTrue(password.getBytes(StandardCharsets.UTF_8).length > 72);

        register("pwdemoji", "pwdemoji@test.com", password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(BYTES));
    }

    @Test
    void exactamente72BytesSeAceptan() throws Exception {
        // 56 caracteres ASCII + 8 eñes = 64 caracteres y 72 bytes: el máximo de BCrypt.
        String password = ascii(56) + "ñ".repeat(8);
        assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length);

        register("pwd72", "pwd72@test.com", password).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Contraseñas comunes
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "password1234", "PASSWORD1234", "  Password1234  ", "123456789012",
            "qwertyuiopas", "contraseña123", "StreamBox123", "    123456    ",
            "aaaaaaaaaaaa", "abcabcabcabc", "            "
    })
    void lasContrasenasComunesOTrivialesSeRechazan(String password) throws Exception {
        register("pwdcommon", "pwdcommon@test.com", password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(COMMON));
    }

    // ------------------------------------------------------------------
    // Datos personales
    // ------------------------------------------------------------------

    @Test
    void noPuedeContenerElNombreDeUsuario() throws Exception {
        register("lucia_dev", "otra.persona@test.com", "MiCuenta-LUCIA_DEV-2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(PERSONAL));
    }

    @Test
    void noPuedeContenerLaParteLocalDelEmail() throws Exception {
        register("mruiz", "Marta.Ruiz@test.com", "xx-marta.ruiz-2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(PERSONAL));
    }

    @Test
    void unaParteLocalDeMenosDe4CaracteresNoSeComprueba() throws Exception {
        // "ab" aparece en muchas contraseñas por casualidad: no se rechaza
        // (el mínimo es 4; el caso de 3 está en el test de casos límite).
        register("pwdshortlocal", "ab@test.com", "Cabra-Montes-77").andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Prioridad: un solo mensaje por campo
    // ------------------------------------------------------------------

    @Test
    void siFallanLongitudYDatosPersonalesSoloSeInformaDeLaLongitud() throws Exception {
        register("pepito", "pepito@test.com", "pepito1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(LENGTH));
    }

    @Test
    void siFallanBytesYDatosPersonalesSoloSeInformaDeLosBytes() throws Exception {
        // 62 caracteres (longitud válida), 117 bytes y contiene el usuario.
        String password = "pepito2" + "áéíóú".repeat(11);
        assertTrue(password.length() >= 12 && password.length() <= 64);
        assertTrue(password.getBytes(StandardCharsets.UTF_8).length > 72);

        register("pepito2", "pepito2@test.com", password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(BYTES));
    }

    @Test
    void siEsComunYContieneElUsuarioSoloSeInformaDeQueEsComun() throws Exception {
        register("password", "password@test.com", "password1234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").value(COMMON));
    }

    @Test
    void losErroresDeOtrosCamposSeSiguenInformando() throws Exception {
        register("ab", "no-es-un-email", "Rio-Tajo-48")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.username").exists())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.password").value(LENGTH));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Contraseña ASCII no trivial de la longitud pedida (sin bloques repetidos cortos). */
    private static String ascii(int length) {
        return "Lince-Iberico-Donana-2026!Halcon-Peregrino-Gredos#Oso-Pardo-Somiedo"
                .substring(0, length);
    }

    private ResultActions register(String username, String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "email", email, "password", password));
        return mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
