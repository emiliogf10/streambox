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
