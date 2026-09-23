package com.emilio.streambox.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para recibir los datos necesarios para crear un nuevo usuario.
 *
 * <p>Contiene las validaciones básicas que deben cumplir los datos
 * proporcionados por el cliente antes de crear la entidad {@code User}.</p>
 */
@Getter
@Setter
public class CreateUserRequest {

    /**
     * Nombre de usuario que tendrá el nuevo usuario.
     *
     * <p>No puede estar vacío ni contener únicamente espacios en blanco.
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
     * <p>El valor recibido debe ser posteriormente cifrado antes de
     * almacenarse en la base de datos. Debe tener entre 8 y 100 caracteres.</p>
     */
    @NotBlank
    @Size(min = 8, max = 100, message = "La contraseña debe tener entre 8 y 100 caracteres")
    private String password;
}