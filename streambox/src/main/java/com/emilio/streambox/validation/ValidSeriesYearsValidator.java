package com.emilio.streambox.validation;

import com.emilio.streambox.dto.SeriesRequest;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementación de {@link ValidSeriesYears}.
 *
 * <p>
 * Solo opina cuando los dos años tienen valor y cada uno está dentro de su
 * rango (1888–2100). Si falta alguno o se sale del rango, ya lo rechazan
 * {@code @NotNull}, {@code @Min} o {@code @Max} del propio campo; quejarse
 * también aquí daría dos errores para {@code endYear} y
 * {@code GlobalExceptionHandler}, que guarda uno por campo, se quedaría con
 * uno cualquiera (Hibernate Validator no garantiza el orden). Es el mismo
 * criterio que sigue {@link HttpsUrlValidator} con {@code @Size}.
 * </p>
 */
public class ValidSeriesYearsValidator implements ConstraintValidator<ValidSeriesYears, SeriesRequest> {

    /**
     * Comprueba que el año de finalización no sea anterior al de estreno.
     *
     * @param request datos de la serie ({@code null} se considera válido)
     * @param context contexto de validación, usado para asignar el error al campo {@code endYear}
     * @return {@code false} solo si ambos años son válidos por separado y
     *         {@code endYear < releaseYear}
     */
    @Override
    public boolean isValid(SeriesRequest request, ConstraintValidatorContext context) {

        if (request == null) {
            return true;
        }
        Integer releaseYear = request.releaseYear();
        Integer endYear = request.endYear();
        if (!inRange(releaseYear) || !inRange(endYear) || endYear >= releaseYear) {
            return true;
        }

        // Por defecto un error de clase no tiene campo; se cuelga de endYear
        // para que llegue al cliente como validationErrors.endYear.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("endYear")
                .addConstraintViolation();
        return false;
    }

    private static boolean inRange(Integer year) {

        return year != null && year >= SeriesRequest.MIN_YEAR && year <= SeriesRequest.MAX_YEAR;
    }
}
