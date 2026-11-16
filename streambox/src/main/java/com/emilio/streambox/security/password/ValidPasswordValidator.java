package com.emilio.streambox.security.password;

import java.util.Optional;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementación de {@link ValidPassword}: delega en
 * {@link PasswordPolicy#findContentViolation(String, String, String)} y, si
 * alguna regla falla, informa de ella en el campo {@code password}.
 */
public class ValidPasswordValidator implements ConstraintValidator<ValidPassword, NewPasswordRequest> {

    /** Nombre del campo al que se asocia el error (el que ve el cliente). */
    static final String PASSWORD_FIELD = "password";

    /**
     * Comprueba la contraseña de la petición.
     *
     * <p>
     * {@code null} y las longitudes fuera de rango se dan por válidas
     * <b>aquí</b> porque ya las rechazan {@code @NotNull} y {@code @Size}; así
     * el campo recibe un único mensaje.
     * </p>
     *
     * <p>
     * <b>El nombre y el email llegan sin acotar.</b> Esta es una restricción de
     * clase del grupo por defecto y el proyecto no usa {@code @GroupSequence}
     * ni {@code fail_fast}, así que Bean Validation la evalúa <i>aunque</i> ya
     * hayan fallado los {@code @Size} de esos campos: en el registro anónimo
     * pueden medir casi los 64 KB del cuerpo. Por eso
     * {@link PasswordPolicy} los recorta en tiempo lineal y no compara los que
     * pasan de {@link PasswordPolicy#MAX_PERSONAL_DATA_INPUT_LENGTH} (ReDoS,
     * NV-1 de la auditoría run-3). Cualquier comprobación nueva con esos datos
     * debe tener coste lineal o acotarlos antes.
     * </p>
     *
     * <p>
     * El mensaje se pasa como plantilla de Bean Validation, que interpreta
     * {@code {...}} y expresiones {@code ${...}}. Es seguro porque siempre es
     * una constante de {@link PasswordPolicy}: nunca debe construirse con datos
     * del usuario (como la propia contraseña), porque permitiría inyectar
     * expresiones.
     * </p>
     *
     * @param request petición validada
     * @param context contexto en el que se registra la violación
     * @return {@code true} si la contraseña cumple las reglas de contenido o le
     *         corresponde a otra restricción
     */
    @Override
    public boolean isValid(NewPasswordRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        String password = request.getPassword();
        if (password == null || !PasswordPolicy.hasAllowedLength(password)) {
            return true;
        }

        Optional<String> violation = PasswordPolicy.findContentViolation(
                password, request.getUsername(), request.getEmail());
        if (violation.isEmpty()) {
            return true;
        }

        // Se sustituye la violación genérica de la clase por una en el campo
        // "password" con el mensaje de la regla incumplida.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(violation.get())
                .addPropertyNode(PASSWORD_FIELD)
                .addConstraintViolation();
        return false;
    }
}
