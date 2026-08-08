package com.emilio.streambox.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
     * <p>No puede estar vacío ni contener únicamente espacios en blanco.</p>
     */
    @NotBlank
    private String username;

    /**
     * Dirección de correo electrónico del nuevo usuario.
     *
     * <p>Debe tener un formato de correo electrónico válido y no puede
     * estar vacía.</p>
     */
    @Email
    @NotBlank
    private String email;

    /**
     * Contraseña proporcionada para el nuevo usuario.
     *
     * <p>El valor recibido debe ser posteriormente cifrado antes de
     * almacenarse en la base de datos.</p>
     */
    @NotBlank
    private String password;
}