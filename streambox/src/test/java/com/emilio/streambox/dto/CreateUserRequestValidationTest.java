package com.emilio.streambox.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Tests unitarios de las restricciones de {@link CreateUserRequest} tal como
 * están anotadas (sin Spring).
 *
 * <p>
 * Lo importante es lo que no se ve en un test de integración: que, aunque la
 * contraseña incumpla varias reglas, Bean Validation produce <b>exactamente
 * una</b> violación en {@code password}. El manejador de errores guarda un
 * mensaje por campo; con dos violaciones el cliente recibiría una u otra según
 * el orden, que no está garantizado.
 * </p>
 */
class CreateUserRequestValidationTest {

    private static final String LENGTH = "La contraseña debe tener entre 12 y 64 caracteres";
    private static final String BYTES =
            "La contraseña es demasiado larga: acórtala o usa menos letras acentuadas, eñes o emojis.";
    private static final String COMMON = "La contraseña es demasiado común. Elige otra más difícil de adivinar.";
    private static final String PERSONAL = "La contraseña no puede contener tu nombre de usuario ni tu email.";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void unaPeticionValidaNoTieneErrores() {
        assertTrue(validator.validate(request("pepito", "pepito@test.com", "Rio-Tajo-482")).isEmpty());
    }

    @Test
    void nulaDaSoloElMensajeDeObligatoria() {
        assertEquals(List.of("La contraseña es obligatoria"), passwordErrors("pepito", "pepito@test.com", null));
    }

    @Test
    void vaciaDaSoloElMensajeDeLongitud() {
        assertEquals(List.of(LENGTH), passwordErrors("pepito", "pepito@test.com", ""));
    }

    @Test
    void cortaComunYConElUsuarioDaSoloLaLongitud() {
        assertEquals(List.of(LENGTH), passwordErrors("pepito", "pepito@test.com", "pepito"));
    }

    @Test
    void largaYConElUsuarioDaSoloLaLongitud() {
        assertEquals(List.of(LENGTH), passwordErrors("pepito", "pepito@test.com", "pepito-" + "x9".repeat(30)));
    }

    @Test
    void conMuchosBytesYElUsuarioDaSoloLosBytes() {
        assertEquals(List.of(BYTES),
                passwordErrors("pepito", "pepito@test.com", "pepito" + "áéíóú".repeat(11)));
    }

    @Test
    void comunYConElUsuarioDaSoloQueEsComun() {
        assertEquals(List.of(COMMON), passwordErrors("password", "password@test.com", "password1234"));
    }

    @Test
    void conElUsuarioOElEmailDaElMensajeDeDatosPersonales() {
        assertEquals(List.of(PERSONAL), passwordErrors("pepito", "otro@test.com", "Soy-Pepito-2026"));
        assertEquals(List.of(PERSONAL), passwordErrors("otro", "Pepito.Grillo@test.com", "x-pepito.grillo-1"));
    }

    @Test
    void elErrorDeContrasenaNoOcultaLosDeOtrosCampos() {
        var violations = validator.validate(request("ab", "no-es-un-email", "password1234"));

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("username")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("email")));
        assertEquals(List.of(COMMON), passwordErrors("ab", "no-es-un-email", "password1234"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private List<String> passwordErrors(String username, String email, String password) {
        return validator.validate(request(username, email, password)).stream()
                .filter(v -> v.getPropertyPath().toString().equals("password"))
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    private static CreateUserRequest request(String username, String email, String password) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }
}
