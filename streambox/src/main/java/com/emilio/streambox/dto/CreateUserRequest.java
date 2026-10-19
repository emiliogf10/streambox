package com.emilio.streambox.dto;

import com.emilio.streambox.security.password.NewPasswordRequest;
import com.emilio.streambox.security.password.PasswordPolicy;
import com.emilio.streambox.security.password.ValidPassword;
import com.emilio.streambox.validation.ControlCharacters;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
     * Longitud máxima del email. Coincide con la columna
     * {@code users.email VARCHAR(100)} (migración {@code V1}); el login usa el
     * mismo tope ({@code LoginRequest}).
     */
    public static final int EMAIL_MAX_LENGTH = 100;

    /** Error de longitud del email (registro, login y comprobación del servicio). */
    public static final String EMAIL_SIZE_MESSAGE =
            "El correo electrónico no puede tener más de " + EMAIL_MAX_LENGTH + " caracteres";

    /** Error cuando falta el email (registro y login). */
    public static final String EMAIL_REQUIRED_MESSAGE = "El correo electrónico es obligatorio";

    /** Error de formato del email (registro y login). */
    public static final String EMAIL_FORMAT_MESSAGE = "Debe tener un formato de correo electrónico válido";

    /**
     * Nombre de usuario que tendrá el nuevo usuario.
     *
     * <p>
     * No puede estar vacío ni contener únicamente espacios en blanco.
     * Debe tener entre 3 y 50 caracteres.</p>
     */
    @NotBlank(message = UpdateProfileRequest.USERNAME_REQUIRED_MESSAGE)
    @Size(min = 3, max = 50, message = "El nombre de usuario debe tener entre 3 y 50 caracteres")
    private String username;

    /**
     * Dirección de correo electrónico del nuevo usuario.
     *
     * <p>Debe tener un formato de correo electrónico válido y no puede
     * estar vacía.</p>
     *
     * <p><b>Máximo de {@value #EMAIL_MAX_LENGTH} caracteres</b>, el de su
     * columna. {@code @Email} no limita la longitud total (solo la de la parte
     * local y la de cada trozo del dominio), así que sin {@code @Size} un email
     * de formato válido pero más largo que la columna llegaba a la base de
     * datos y el cliente recibía un 409 {@code DATA_INTEGRITY_VIOLATION}
     * genérico en lugar de un 400 que dijera qué pasa. Un email demasiado largo
     * <em>y</em> mal formado incumple dos reglas y recibe uno de los dos
     * mensajes (Bean Validation no garantiza el orden); ambos son ciertos.</p>
     *
     * <p><b>Sin caracteres de control</b> ({@link ControlCharacters#SINGLE_LINE_TEXT}),
     * con el mismo mensaje que el formato: {@code @Email} deja pasar los de
     * {@code U+0080} a {@code U+009F} (p. ej. {@code U+0085}, «siguiente
     * línea»), que acabarían en la base de datos, en los logs y en la cabecera
     * {@code To} del correo. Un email con uno no es un email válido, así que
     * el mensaje es verdad; y al ser el mismo que el de {@code @Email}, da igual
     * cuál de los dos salte primero.</p>
     */
    @Email(message = EMAIL_FORMAT_MESSAGE)
    @Pattern(regexp = ControlCharacters.SINGLE_LINE_TEXT, message = EMAIL_FORMAT_MESSAGE)
    @NotBlank(message = EMAIL_REQUIRED_MESSAGE)
    @Size(max = EMAIL_MAX_LENGTH, message = EMAIL_SIZE_MESSAGE)
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
