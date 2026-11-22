package com.emilio.streambox.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Tests unitarios de las reglas de {@link WatchProgressRequest} tal como están
 * anotadas (sin Spring).
 *
 * <p>
 * Lo que tiene lógica propia es la regla de clase
 * {@code @PositionWithinDuration}: debe colgar el error de
 * {@code positionSeconds} y no sumarse a los errores de rango de cada campo
 * (un solo error por campo). Los mensajes van literales para que cambiar el
 * contrato por descuido rompa el test.
 * </p>
 */
class WatchProgressRequestValidationTest {

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

    /** Los extremos válidos: empezar (0), terminar (posición = duración) y 24 h. */
    @Test
    void losExtremosValidosNoTienenErrores() {
        assertEquals(Map.of(), errors(0, 1));
        assertEquals(Map.of(), errors(1, 1));
        assertEquals(Map.of(), errors(86_400, 86_400));
        assertEquals(Map.of(), errors(1325, 8160));
    }

    @Test
    void unaPosicionMayorQueLaDuracionSeCuelgaDePositionSeconds() {
        assertEquals(Map.of("positionSeconds", "La posición no puede ser mayor que la duración"), errors(101, 100));
    }

    @Test
    void cadaCampoFueraDeRangoDaSuPropioMensaje() {
        assertEquals(Map.of("positionSeconds", "La posición no puede ser negativa"), errors(-1, 100));
        assertEquals(Map.of("durationSeconds", "La duración debe ser de al menos 1 segundo"), errors(0, 0));
        assertEquals(Map.of("durationSeconds", "La duración no puede superar las 24 horas"),
                errors(10, 86_401));
        assertEquals(Map.of("positionSeconds", "La posición es obligatoria"), errors(null, 100));
        assertEquals(Map.of("durationSeconds", "La duración es obligatoria"), errors(10, null));
    }

    /**
     * Con la duración fuera de rango la regla de clase no opina: la posición
     * 90000 supera la duración 86401, pero el único error es el de la
     * duración (si no, habría dos para corregir algo que es uno).
     */
    @Test
    void laReglaDeClaseNoSeSumaAUnRangoIncorrecto() {
        assertEquals(Map.of("durationSeconds", "La duración no puede superar las 24 horas"),
                errors(90_000, 86_401));
        assertEquals(Map.of("positionSeconds", "La posición no puede ser negativa",
                "durationSeconds", "La duración debe ser de al menos 1 segundo"), errors(-5, 0));
    }

    /** Campo → mensaje; falla si un campo tuviera más de un error. */
    private static Map<String, String> errors(Integer position, Integer duration) {
        Set<ConstraintViolation<WatchProgressRequest>> violations =
                validator.validate(new WatchProgressRequest(position, duration));
        return violations.stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
