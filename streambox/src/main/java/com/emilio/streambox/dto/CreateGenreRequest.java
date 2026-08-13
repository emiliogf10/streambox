package com.emilio.streambox.dto;

import jakarta.validation.constraints.NotBlank;
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
     */
    @NotBlank
    private String name;
}