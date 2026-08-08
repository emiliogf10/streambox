package com.emilio.streambox.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
     * Dirección de correo electrónico utilizada para iniciar sesión.
     *
     * <p>Debe tener un formato de correo electrónico válido y no puede
     * estar vacía.</p>
     */
    @Email
    @NotBlank
    private String email;

    /**
     * Contraseña proporcionada por el usuario durante el inicio de sesión.
     *
     * <p>Este valor se utiliza para comprobar las credenciales mediante
     * el mecanismo de autenticación de la aplicación.</p>
     */
    @NotBlank
    private String password;
}