package com.emilio.streambox.dto;

import com.emilio.streambox.security.password.NewPasswordRequest;
import com.emilio.streambox.security.password.PasswordPolicy;
import com.emilio.streambox.security.password.ValidPassword;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para recibir los datos necesarios para crear un nuevo usuario.
 *
 * <p>Contiene las validaciones que deben cumplir los datos proporcionados por
 * el cliente antes de crear la entidad {@code User}. La contraseña sigue la
 * {@link PasswordPolicy}: la obligatoriedad y la longitud se validan en el
 * campo y el resto de reglas con {@link ValidPassword} sobre la clase, porque
 * necesitan también el nombre de usuario y el email. Cada caso produce un
 * único mensaje en {@code validationErrors.password}.</p>
 *
 * <p>Sigue siendo una clase Lombok (y no un {@code record}) porque
 * {@code UserService} usa sus getters; convertirla no cambiaría el JSON.</p>
 */
@Getter
@Setter
@ValidPassword
public class CreateUserRequest implements NewPasswordRequest {

    /**
     * Nombre de usuario que tendrá el nuevo usuario.
     *
     * <p>
     * No puede estar vacío ni contener únicamente espacios en blanco.
     * Debe tener entre 3 y 50 caracteres.</p>
     */
    @NotBlank
    @Size(min = 3, max = 50, message = "El nombre de usuario debe tener entre 3 y 50 caracteres")
    private String username;

    /**
     * Dirección de correo electrónico del nuevo usuario.
     *
     * <p>Debe tener un formato de correo electrónico válido y no puede
     * estar vacía.</p>
     */
    @Email(message = "Debe tener un formato de correo electrónico válido")
    @NotBlank
    private String email;

    /**
     * Contraseña proporcionada para el nuevo usuario.
     *
     * <p>Se cifra con BCrypt antes de guardarse. Debe tener entre
     * {@value PasswordPolicy#MIN_LENGTH} y {@value PasswordPolicy#MAX_LENGTH}
     * caracteres.</p>
     *
     * <p>Se usa {@code @NotNull} y no {@code @NotBlank}: con {@code @NotBlank}
     * una contraseña vacía incumpliría dos restricciones a la vez (también
     * {@code @Size}) y el mensaje que recibe el cliente dependería del orden,
     * que Bean Validation no garantiza. Una contraseña de solo espacios con
     * longitud suficiente la rechaza {@link ValidPassword} como trivial.</p>
     */
    @Schema(description = "Contraseña de la cuenta: entre 12 y 64 caracteres y como máximo 72 bytes "
            + "en UTF-8 (las tildes y eñes ocupan 2 bytes y los emojis 4). No puede ser una "
            + "contraseña común o trivial ni contener el nombre de usuario o la parte del email "
            + "anterior a la @. No se exigen mayúsculas, números ni símbolos: se recomienda una "
            + "frase larga.",
            example = "un paseo por el retiro en otoño")
    @NotNull(message = PasswordPolicy.REQUIRED_MESSAGE)
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
            message = PasswordPolicy.LENGTH_MESSAGE)
    private String password;
}
