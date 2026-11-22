package com.emilio.streambox.validation;

import com.emilio.streambox.dto.WatchProgressRequest;
import com.emilio.streambox.repository.WatchProgress;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementación de {@link PositionWithinDuration}.
 *
 * <p>
 * Solo opina cuando los dos valores existen y cada uno está en su rango
 * (posición ≥ 0, duración 1..{@value WatchProgress#MAX_DURATION_SECONDS}). Si
 * no, ya lo rechazan {@code @NotNull}, {@code @Min} o {@code @Max} del propio
 * campo; quejarse también aquí daría dos errores para {@code positionSeconds}
 * y {@code GlobalExceptionHandler}, que guarda uno por campo, se quedaría con
 * uno cualquiera. Mismo criterio que {@link ValidSeriesYearsValidator}.
 * </p>
 */
public class PositionWithinDurationValidator
        implements ConstraintValidator<PositionWithinDuration, WatchProgressRequest> {

    /**
     * Comprueba que la posición no supere la duración.
     *
     * @param request progreso enviado ({@code null} se considera válido)
     * @param context contexto de validación, usado para asignar el error al
     *                campo {@code positionSeconds}
     * @return {@code false} solo si los dos valores son válidos por separado y
     *         {@code positionSeconds > durationSeconds}
     */
    @Override
    public boolean isValid(WatchProgressRequest request, ConstraintValidatorContext context) {

        if (request == null) {
            return true;
        }
        Integer position = request.positionSeconds();
        Integer duration = request.durationSeconds();
        if (position == null || duration == null || position < 0
                || duration < 1 || duration > WatchProgress.MAX_DURATION_SECONDS
                || position <= duration) {
            return true;
        }

        // Un error de clase no tiene campo por defecto: se cuelga de
        // positionSeconds para que llegue como validationErrors.positionSeconds.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("positionSeconds")
                .addConstraintViolation();
        return false;
    }
}
