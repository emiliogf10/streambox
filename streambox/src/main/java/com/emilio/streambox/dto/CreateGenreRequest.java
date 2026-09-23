package com.emilio.streambox.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para recibir los datos necesarios para crear
 * un nuevo género cinematográfico.
 */
@Getter
@Setter
public class CreateGenreRequest {

    /**
     * Nombre del género cinematográfico.
     *
     * <p>El nombre es obligatorio, debe tener entre 2 y 50 caracteres
     * y no puede repetirse dentro de la base de datos.</p>
     */
    @NotBlank
    @Size(min = 2, max = 50, message = "El nombre del género debe tener entre 2 y 50 caracteres")
    private String name;
}