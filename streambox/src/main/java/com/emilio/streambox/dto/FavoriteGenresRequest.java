package com.emilio.streambox.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de {@code PUT /api/users/me/genres}: la lista <strong>completa</strong>
 * de géneros favoritos del usuario («Tus gustos»), que sustituye a la anterior.
 *
 * <p>
 * <b>Por qué aquí solo {@code @NotNull}.</b> El resto de reglas (lista vacía o
 * entre {@value #MIN_GENRES} y {@value #MAX_GENRES} ids, sin repetidos, de
 * géneros que existan y tengan títulos) las comprueba
 * {@code FavoriteGenreService}: «vacía o entre 3 y 5» no se puede expresar con
 * {@code @Size} (que solo admite un intervalo), la existencia depende de la base
 * de datos, y así cada fallo llega con un mensaje propio y siempre en
 * {@code validationErrors.genreIds} (un {@code @NotNull} en los elementos lo
 * pondría en {@code genreIds[1]}).
 * </p>
 *
 * @param genreIds ids de los géneros elegidos; vacía para no tener ninguno
 */
@Schema(description = "Lista completa de géneros favoritos: sustituye a la anterior.")
public record FavoriteGenresRequest(
        @NotNull(message = NULL_MESSAGE)
        @ArraySchema(arraySchema = @Schema(description = "Ids de los géneros elegidos: entre 3 y 5 distintos, de "
                + "géneros con algún título (los de GET /api/users/me/genres/options), o una lista vacía para "
                + "no tener ninguno."), schema = @Schema(type = "integer", format = "int64", example = "3"))
        List<Long> genreIds) {

    /** Mínimo de géneros si la lista no está vacía (decisión del autor). */
    public static final int MIN_GENRES = 3;

    /** Máximo de géneros (decisión del autor): pocos, para que cada uno pese de verdad. */
    public static final int MAX_GENRES = 5;

    /** Mensaje si falta {@code genreIds} o es {@code null}. */
    public static final String NULL_MESSAGE =
            "Indica los géneros: entre 3 y 5, o una lista vacía si no quieres elegir ninguno.";
}
