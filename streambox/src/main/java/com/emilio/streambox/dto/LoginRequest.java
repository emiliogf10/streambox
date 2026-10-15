package com.emilio.streambox.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para recibir las credenciales necesarias
 * para autenticar a un usuario.
 *
 * <p>Contiene el correo electrónico y la contraseña proporcionados
 * por el cliente durante el inicio de sesión.</p>
 */
@Getter
@Setter
public class LoginRequest {

    /**
     * Tope de longitud de la contraseña del login: una protección contra
     * cuerpos enormes, no una regla de la política (ver {@link #password}).
     */
    public static final int MAX_PASSWORD_LENGTH = 1024;

    /**
     * Dirección de correo electrónico utilizada para iniciar sesión.
     *
     * <p>Debe tener un formato de correo electrónico válido y no puede
     * estar vacía.</p>
     *
     * <p><b>Máximo de {@value CreateUserRequest#EMAIL_MAX_LENGTH} caracteres</b>,
     * el mismo que el registro y la columna {@code users.email}. No puede dejar
     * fuera a ninguna cuenta existente: ninguna puede tener un email más largo
     * que su columna. Así un email enorme recibe un 400 antes de llegar a
     * {@code AuthenticationService}: no se normaliza, no se consulta en la base
     * de datos ni se guarda como clave de los contadores de intentos (que
     * viven en memoria). Los mensajes son los del registro.</p>
     */
    @Email(message = CreateUserRequest.EMAIL_FORMAT_MESSAGE)
    @NotBlank(message = CreateUserRequest.EMAIL_REQUIRED_MESSAGE)
    @Size(max = CreateUserRequest.EMAIL_MAX_LENGTH, message = CreateUserRequest.EMAIL_SIZE_MESSAGE)
    private String email;

    /**
     * Contraseña proporcionada por el usuario durante el inicio de sesión.
     *
     * <p>Este valor se utiliza para comprobar las credenciales mediante
     * el mecanismo de autenticación de la aplicación.</p>
     *
     * <p><b>A propósito, no tiene mínimo: solo se exige que no esté vacía.</b>
     * La política de contraseñas ({@code PasswordPolicy}: 12 a 64 caracteres,
     * no común...) se aplica al crear una cuenta, no al entrar: las cuentas
     * creadas con la política anterior (mínimo 8 caracteres) deben poder seguir
     * iniciando sesión. Rechazarlas aquí no aportaría seguridad: lo que frena
     * los intentos de adivinar contraseñas es el bloqueo de cuenta y el límite
     * por IP, no la forma de la contraseña enviada.</p>
     *
     * <p><b>Máximo de {@value #MAX_PASSWORD_LENGTH} caracteres.</b> No es una
     * regla de la política (ninguna contraseña válida pasa de 64), sino un tope
     * generoso para no procesar cuerpos enormes: sin él, cualquiera podría
     * enviar megas de texto en cada intento y obligar al servidor a leerlos y
     * pasarlos a BCrypt. Como Bean Validation actúa antes de llegar a
     * {@code AuthenticationService}, una contraseña más larga recibe un 400 y
     * <b>no gasta intento</b> de la cuenta (sí cuenta para el límite por IP,
     * que se aplica antes que nada).</p>
     */
    @NotBlank(message = "La contraseña es obligatoria")
    @Size(max = MAX_PASSWORD_LENGTH,
            message = "La contraseña no puede tener más de " + MAX_PASSWORD_LENGTH + " caracteres")
    private String password;
}