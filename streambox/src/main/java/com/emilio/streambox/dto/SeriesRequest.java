package com.emilio.streambox.dto;

import java.util.Set;

import com.emilio.streambox.validation.HttpsUrl;
import com.emilio.streambox.validation.ValidSeriesYears;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO con los datos de una serie, utilizado tanto para crearla
 * ({@code POST /api/series}) como para modificarla ({@code PUT /api/series/{id}}).
 *
 * <p>
 * Igual que {@link MovieRequest}, {@code PUT} sustituye la serie completa y
 * basta un solo DTO. Los episodios no van aquí: se gestionan uno a uno con
 * {@link EpisodeRequest}.
 * </p>
 *
 * <p>
 * <b>Mensajes.</b> Todas las restricciones llevan su mensaje en español
 * explícito, en lugar del mensaje por defecto de Hibernate Validator, que
 * depende del idioma de la JVM. Los de la URL de la portada y las longitudes
 * de título y descripción son <em>las mismas constantes y textos</em> que en
 * {@link MovieRequest}, para que el formulario del frontend muestre lo mismo
 * en películas y series.
 * </p>
 *
 * <p>
 * La regla "{@code endYear} no anterior a {@code releaseYear}" compara dos
 * campos, así que la comprueba {@link ValidSeriesYears} a nivel de clase (el
 * error llega al cliente en {@code validationErrors.endYear}).
 * </p>
 *
 * @param title       título (obligatorio, máximo 150 caracteres)
 * @param description sinopsis (obligatoria, máximo 1000 caracteres)
 * @param releaseYear año de estreno (obligatorio, entre 1888 y 2100)
 * @param endYear     año de finalización (opcional: {@code null} = en emisión;
 *                    entre 1888 y 2100 y no anterior a {@code releaseYear})
 * @param imageUrl    URL de la portada (obligatoria, máximo 500 caracteres,
 *                    {@code https://} o {@code /covers/<archivo>})
 * @param genreIds    identificadores de los géneros (entre 1 y
 *                    {@value MovieRequest#MAX_GENRES})
 */
@ValidSeriesYears
public record SeriesRequest(

        @NotBlank(message = "El título es obligatorio")
        @Size(max = 150, message = "El título no puede superar los 150 caracteres")
        String title,

        @NotBlank(message = "La descripción es obligatoria")
        @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres")
        String description,

        @NotNull(message = "El año de estreno es obligatorio")
        @Min(value = SeriesRequest.MIN_YEAR, message = SeriesRequest.RELEASE_YEAR_RANGE_MESSAGE)
        @Max(value = SeriesRequest.MAX_YEAR, message = SeriesRequest.RELEASE_YEAR_RANGE_MESSAGE)
        Integer releaseYear,

        @Schema(description = "Año en que terminó la serie; null o ausente si sigue en emisión. "
                + "Si se indica, entre 1888 y 2100 y no anterior a releaseYear.")
        @Min(value = SeriesRequest.MIN_YEAR, message = SeriesRequest.END_YEAR_RANGE_MESSAGE)
        @Max(value = SeriesRequest.MAX_YEAR, message = SeriesRequest.END_YEAR_RANGE_MESSAGE)
        Integer endYear,

        @Schema(description = "URL de la portada. Debe ser una URL https:// absoluta, con host y sin "
                + "credenciales (usuario:clave@), o una portada propia del frontend con la forma "
                + "/covers/<archivo>, donde el archivo empieza por letra o número y solo contiene "
                + "letras, números, punto, guion y guion bajo. Solo caracteres ASCII visibles "
                + "(sin espacios); máximo 500 caracteres.",
                example = "https://image.tmdb.org/t/p/w500/portada.jpg")
        @NotBlank(message = "La URL de la imagen es obligatoria")
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.IMAGE_URL_SIZE_MESSAGE)
        @HttpsUrl(allowLocalCovers = true, maxLength = MovieRequest.URL_MAX_LENGTH,
                message = MovieRequest.IMAGE_URL_FORMAT_MESSAGE)
        String imageUrl,

        @NotEmpty(message = "Indica al menos un género")
        @Size(max = MovieRequest.MAX_GENRES, message = SeriesRequest.GENRES_SIZE_MESSAGE)
        Set<@NotNull(message = "Los identificadores de género no pueden ser nulos") Long> genreIds) {

    /**
     * Error de cardinalidad de {@code genreIds}. El tope es el mismo que el de
     * las películas ({@link MovieRequest#MAX_GENRES}, donde se explica por qué
     * existe); solo cambia la palabra del mensaje.
     */
    public static final String GENRES_SIZE_MESSAGE =
            "Una serie puede tener como máximo " + MovieRequest.MAX_GENRES + " géneros";

    /** Primer año válido (el mismo límite que las películas y que {@code ck_series_release_year}). */
    public static final int MIN_YEAR = 1888;

    /** Último año válido (el mismo límite que las películas y que la base de datos). */
    public static final int MAX_YEAR = 2100;

    /** Error de rango de {@code releaseYear}. */
    public static final String RELEASE_YEAR_RANGE_MESSAGE = "El año de estreno debe estar entre 1888 y 2100";

    /** Error de rango de {@code endYear}. */
    public static final String END_YEAR_RANGE_MESSAGE = "El año de finalización debe estar entre 1888 y 2100";
}
