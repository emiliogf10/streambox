package com.emilio.streambox.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO con los datos de un género, utilizado tanto para crearlo
 * ({@code POST /api/genres}) como para renombrarlo ({@code PUT /api/genres/{id}}).
 *
 * <p>
 * Sustituye a la antigua clase Lombok {@code CreateGenreRequest}: al añadir la
 * edición, ambas operaciones reciben exactamente el mismo campo con las mismas
 * reglas, y un {@code record} inmutable lo expresa sin código generado (igual
 * que {@link MovieRequest}, que también sirve para alta y edición). El JSON de
 * la API no cambia: sigue siendo {@code {"name": "..."}}.
 * </p>
 *
 * <p>
 * Las anotaciones son la primera barrera (y lo que publica OpenAPI), pero se
 * evalúan sobre el texto <em>recibido</em>. El servicio después lo normaliza
 * (quita espacios exteriores, colapsa los interiores y ajusta mayúsculas) y
 * vuelve a comprobar la longitud sobre el resultado, porque normalizar puede
 * acortarlo o alargarlo. Las constantes de longitud y el mensaje se comparten
 * con {@code GenreService} para que ambas comprobaciones apliquen la misma
 * regla y el cliente vea siempre el mismo texto.
 * </p>
 *
 * @param name nombre del género (obligatorio, entre 2 y 50 caracteres, único)
 */
public record GenreRequest(

        @NotBlank(message = "El nombre del género es obligatorio")
        @Size(min = GenreRequest.NAME_MIN_LENGTH, max = GenreRequest.NAME_MAX_LENGTH,
                message = GenreRequest.NAME_SIZE_MESSAGE)
        String name) {

    /** Longitud mínima del nombre (en unidades UTF-16, como cuenta {@code @Size}). */
    public static final int NAME_MIN_LENGTH = 2;

    /**
     * Longitud máxima del nombre. Coincide con la columna
     * {@code genres.name VARCHAR(50)}.
     */
    public static final int NAME_MAX_LENGTH = 50;

    /** Mensaje de error de longitud, común a {@code @Size} y al servicio. */
    public static final String NAME_SIZE_MESSAGE = "El nombre del género debe tener entre 2 y 50 caracteres";
}
