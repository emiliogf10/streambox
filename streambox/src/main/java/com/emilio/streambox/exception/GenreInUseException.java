package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando se intenta borrar un género que todavía tienen
 * asignado una o más películas (HTTP 409).
 *
 * <p>
 * Se ha preferido impedir el borrado antes que quitar el género de esas
 * películas en cascada: toda película debe tener al menos un género
 * ({@code MovieRequest.genreIds} es {@code @NotEmpty}) y una cascada podría
 * dejar alguna sin ninguno sin que el administrador lo sepa. La propia base de
 * datos ya lo impide (la clave foránea {@code fk_movie_genres_genre} no tiene
 * {@code ON DELETE CASCADE}); esta excepción convierte ese rechazo en un error
 * claro para el cliente.
 * </p>
 */
public class GenreInUseException extends RuntimeException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje que explica por qué no se puede borrar el género
     */
    public GenreInUseException(String message) {
        super(message);
    }
}
